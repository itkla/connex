package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;

class AiChatPrivacyOutboundInterceptorTest {
    private final AtomicInteger generation = new AtomicInteger(1);
    private final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    @Test
    void delayedOutboundExecutorDropsContentQueuedBeforeInvalidationCommitted() {
        var guard = guard();
        Queue<Runnable> delayed = new ArrayDeque<>();
        var inbound = new ExecutorSubscribableChannel();
        var brokerChannel = new ExecutorSubscribableChannel();
        var outbound = new ExecutorSubscribableChannel(delayed::add);
        outbound.addInterceptor(new AiChatPrivacyOutboundInterceptor(guard));
        AtomicInteger received = new AtomicInteger();
        outbound.subscribe(message -> {
            if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
                received.incrementAndGet();
                var nativeHeaders = SimpMessageHeaderAccessor.wrap(message).getNativeHeader(
                        AiChatPrivacyOutboundInterceptor.class.getName() + ".authority");
                org.junit.jupiter.api.Assertions.assertNull(nativeHeaders);
            }
        });
        var broker = new SimpleBrokerMessageHandler(inbound, outbound, brokerChannel, List.of("/queue"));
        var users = mock(SimpUserRegistry.class);
        var user = mock(SimpUser.class);
        var socket = mock(SimpSession.class);
        when(users.getUser("opaque-user")).thenReturn(user);
        when(user.getSessions()).thenReturn(Set.of(socket));
        when(socket.getId()).thenReturn("socket");
        var destinations = new UserDestinationMessageHandler(inbound, brokerChannel, new DefaultUserDestinationResolver(users));
        broker.start();
        destinations.start();
        try {
            var connect = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
            connect.setSessionId("socket");
            inbound.send(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));
            delayed.remove().run();
            var subscription = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
            subscription.setSessionId("socket");
            subscription.setSubscriptionId("subscription");
            subscription.setDestination("/queue/ai-chat-usersocket");
            inbound.send(MessageBuilder.createMessage(new byte[0], subscription.getMessageHeaders()));
            var template = new SimpMessagingTemplate(brokerChannel);
            template.convertAndSendToUser("opaque-user", "/queue/ai-chat", "old content", AiChatPrivacyOutboundInterceptor.headers(7, 1));
            assertEquals(1, delayed.size());
            invalidate();
            delayed.remove().run();
            assertEquals(0, received.get());
            template.convertAndSendToUser("opaque-user", "/queue/ai-chat", "new content", AiChatPrivacyOutboundInterceptor.headers(7, 2));
            delayed.remove().run();
            assertEquals(1, received.get());
        } finally {
            destinations.stop();
            broker.stop();
        }
    }

    @Test
    void invalidationWaitsUntilActualSocketWriteFinishes() throws Exception {
        var outbound = new ExecutorSubscribableChannel();
        var guard = guard();
        var interceptor = new AiChatPrivacyOutboundInterceptor(guard);
        outbound.addInterceptor(interceptor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invalidating = new CountDownLatch(1);
        var invalidator = new AtomicReference<Thread>();
        var socket = mock(WebSocketSession.class);
        doAnswer(call -> {
            entered.countDown();
            await(release);
            assertEquals(1, generation.get());
            return null;
        }).when(socket).sendMessage(any());
        var buffered = new AiChatPrivacyWebSocketSession(socket, 10_000, 512_000, interceptor, guard);
        outbound.subscribe(message -> {
            try {
                buffered.sendMessage(new TextMessage("assistant delta"));
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var delivering = workers.submit(() -> outbound.send(MessageBuilder.createMessage(
                    new byte[0], AiChatPrivacyOutboundInterceptor.headers(7, 1))));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var invalidation = workers.submit(() -> {
                invalidator.set(Thread.currentThread());
                invalidating.countDown();
                invalidate();
            });
            try {
                assertTrue(invalidating.await(10, TimeUnit.SECONDS));
                awaitPublicationWriteWait(invalidator.get());
                assertEquals(1, generation.get());
            } finally {
                release.countDown();
            }
            assertTrue(delivering.get(10, TimeUnit.SECONDS));
            invalidation.get(10, TimeUnit.SECONDS);
            assertEquals(2, generation.get());
        }
    }

    @Test
    void bufferedAssistantFrameIsDroppedAfterInvalidationWhileOtherFramesStillFlush() throws Exception {
        var guard = guard();
        var interceptor = new AiChatPrivacyOutboundInterceptor(guard);
        var outbound = new ExecutorSubscribableChannel();
        outbound.addInterceptor(interceptor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<WebSocketMessage<?>> written = new CopyOnWriteArrayList<>();
        var socket = mock(WebSocketSession.class);
        var stalled = new TextMessage("stalled notification");
        doAnswer(call -> {
            WebSocketMessage<?> frame = call.getArgument(0);
            if (frame == stalled) {
                entered.countDown();
                await(release);
            }
            written.add(frame);
            return null;
        }).when(socket).sendMessage(any());
        ConcurrentWebSocketSessionDecorator buffered = new AiChatPrivacyWebSocketSession(
                socket, 10_000, 512_000, interceptor, guard);
        var assistant = new TextMessage("withdrawn assistant delta");
        outbound.subscribe(message -> {
            try {
                buffered.sendMessage(assistant);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var sending = worker.submit(() -> {
                buffered.sendMessage(stalled);
                return null;
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTrue(outbound.send(MessageBuilder.createMessage(
                        new byte[0], AiChatPrivacyOutboundInterceptor.headers(7, 1))));
                assertEquals(assistant.getPayloadLength(), buffered.getBufferSize());
                var heartbeat = new TextMessage("\n");
                var notification = new TextMessage("ordinary notification");
                buffered.sendMessage(heartbeat);
                buffered.sendMessage(notification);
                invalidate();
                assertEquals(2, generation.get());
                release.countDown();
                sending.get(10, TimeUnit.SECONDS);
                assertEquals(List.of(stalled, heartbeat, notification), written);
                assertEquals(0, buffered.getBufferSize());
                assertEquals(10_000, buffered.getSendTimeLimit());
                assertEquals(512_000, buffered.getBufferSizeLimit());
                assertEquals(ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE, buffered.getOverflowStrategy());
                assertTrue(outbound.send(MessageBuilder.createMessage(
                        new byte[0], AiChatPrivacyOutboundInterceptor.headers(7, 2))));
                assertEquals(assistant, written.getLast());
            } finally {
                release.countDown();
            }
        }
    }

    private AiAssistantPrivacyPublicationGuard guard() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(authority.current(7)).thenAnswer(call -> (long) generation.get());
        return new AiAssistantPrivacyPublicationGuard(authority, transactions);
    }

    private void invalidate() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            AiAssistantPrivacyPublicationGuard.retainInvalidation(7);
            generation.incrementAndGet();
        } finally {
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            TransactionSynchronizationManager.clear();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitPublicationWriteWait(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING
                    && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().contains("ReentrantReadWriteLock") && frame.getMethodName().equals("lock"))) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Invalidation did not wait on the publication write fence");
    }
}
