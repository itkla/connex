package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TenantExportExecutionTest {

    @Test
    void repeatedNewCancellationCleansUpExactlyOnce() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            AtomicInteger cleanups = new AtomicInteger();
            CountDownLatch cleaned = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> {
                    cleanups.incrementAndGet();
                    cleaned.countDown();
                    return failure;
                });

            execution.cancel();
            execution.cancel();

            assertTrue(cleaned.await(1, TimeUnit.SECONDS));
            assertEquals(1, cleanups.get());
            assertThrows(IllegalStateException.class, execution::begin);
        }
    }

    @Test
    void cancellationClosesStrictlyLifoAndContinuesAfterFailures() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            List<String> order = new CopyOnWriteArrayList<>();
            IOException newestFailure = new IOException("newest");
            IOException middleFailure = new IOException("middle");
            AtomicBoolean failNewest = new AtomicBoolean(true);
            AtomicBoolean failMiddle = new AtomicBoolean(true);
            CountDownLatch firstPassCompleted = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> failure);
            execution.begin();
            execution.track(() -> {
                order.add("oldest");
                firstPassCompleted.countDown();
            });
            execution.track(() -> {
                order.add("middle");
                if (failMiddle.getAndSet(false)) {
                    throw middleFailure;
                }
            });
            execution.track(() -> {
                order.add("newest");
                if (failNewest.getAndSet(false)) {
                    throw newestFailure;
                }
            });

            execution.cancel();
            assertTrue(firstPassCompleted.await(1, TimeUnit.SECONDS));
            IOException thrown = assertThrows(
                IOException.class,
                () -> execution.writerFinished(null));

            assertEquals(List.of("newest", "middle", "oldest", "newest", "middle"), order);
            assertSame(newestFailure, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(middleFailure, thrown.getSuppressed()[0]);
        }
    }

    @Test
    void blockedOlderSinkCannotDelayNewerStatementAndProviderCancellation()
            throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch sinkCloseEntered = new CountDownLatch(1);
            CountDownLatch releaseSink = new CountDownLatch(1);
            CountDownLatch providerClosed = new CountDownLatch(1);
            CountDownLatch statementClosed = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> failure);
            execution.begin();
            execution.track(() -> {
                sinkCloseEntered.countDown();
                await(releaseSink);
            });
            execution.track(providerClosed::countDown);
            execution.track(statementClosed::countDown);

            execution.cancel();

            assertTrue(statementClosed.await(1, TimeUnit.SECONDS));
            assertTrue(providerClosed.await(1, TimeUnit.SECONDS));
            assertTrue(sinkCloseEntered.await(1, TimeUnit.SECONDS));
            releaseSink.countDown();
            execution.writerFinished(null);
        }
    }

    @Test
    void cancellationSignalReturnsWhileTrackedCleanupIsBlocked() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors();
                ExecutorService caller = Executors.newSingleThreadExecutor()) {
            CountDownLatch closeEntered = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> failure);
            execution.begin();
            execution.track(() -> {
                closeEntered.countDown();
                await(releaseClose);
            });

            Future<?> cancellation = caller.submit(execution::cancel);

            cancellation.get(250, TimeUnit.MILLISECONDS);
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            releaseClose.countDown();
            execution.writerFinished(null);
        }
    }

    @Test
    void fourAdmittedCancellationsSaturateWorkersWithoutDuplicateDispatch() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch closeEntered = new CountDownLatch(4);
            CountDownLatch releaseClose = new CountDownLatch(1);
            List<TenantExportExecution> executions = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                TenantExportExecution execution = executors.execution(
                    Duration.ofSeconds(5),
                    failure -> failure);
                execution.begin();
                execution.track(() -> {
                    closeEntered.countDown();
                    await(releaseClose);
                });
                executions.add(execution);
                execution.cancel();
                execution.cancel();
            }

            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(executors.cancellation().getQueue().isEmpty());
            releaseClose.countDown();
            for (TenantExportExecution execution : executions) {
                execution.writerFinished(null);
            }
        }
    }

    @Test
    void interruptedWriterWaitsForCancellationBeforeCleanupAndRestoresInterrupt()
            throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch closeEntered = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1);
            AtomicBoolean closed = new AtomicBoolean();
            AtomicBoolean cleanupBeforeClose = new AtomicBoolean();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            CountDownLatch writerEntered = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> {
                    cleanupBeforeClose.set(!closed.get());
                    return failure;
                });
            execution.begin();
            execution.track(() -> {
                closeEntered.countDown();
                await(releaseClose);
                closed.set(true);
            });
            execution.cancel();
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));

            Thread writer = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                writerEntered.countDown();
                try {
                    execution.writerFinished(null);
                } catch (Throwable failure) {
                    writerFailure.set(failure);
                }
                interruptRestored.set(Thread.currentThread().isInterrupted());
            });

            assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
            awaitWaiting(writer);
            assertTrue(writer.isAlive());
            assertFalse(cleanupBeforeClose.get());
            releaseClose.countDown();
            writer.join(2_000);

            assertFalse(writer.isAlive());
            assertNull(writerFailure.get());
            assertFalse(cleanupBeforeClose.get());
            assertTrue(interruptRestored.get());
        }
    }

    @Test
    void writerWaitsForAnAcceptedQueuedCancellationBeforeCleanup() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch workersEntered = new CountDownLatch(4);
            CountDownLatch releaseWorkers = new CountDownLatch(1);
            try {
                for (int index = 0; index < 4; index++) {
                    executors.cancellation().execute(() -> {
                        workersEntered.countDown();
                        await(releaseWorkers);
                    });
                }
                assertTrue(workersEntered.await(1, TimeUnit.SECONDS));
                AtomicBoolean closed = new AtomicBoolean();
                AtomicBoolean cleanupBeforeClose = new AtomicBoolean();
                AtomicReference<Throwable> writerFailure = new AtomicReference<>();
                CountDownLatch writerEntered = new CountDownLatch(1);
                TenantExportExecution execution = executors.execution(
                    Duration.ofSeconds(5),
                    failure -> {
                        cleanupBeforeClose.set(!closed.get());
                        return failure;
                    });
                execution.begin();
                execution.track(() -> closed.set(true));
                execution.cancel();
                executors.cancellation().shutdown();

                Thread writer = Thread.ofPlatform().start(() -> {
                    writerEntered.countDown();
                    try {
                        execution.writerFinished(null);
                    } catch (Throwable failure) {
                        writerFailure.set(failure);
                    }
                });

                assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
                awaitWaiting(writer);
                assertTrue(writer.isAlive());
                assertFalse(cleanupBeforeClose.get());
                releaseWorkers.countDown();
                writer.join(2_000);

                assertFalse(writer.isAlive());
                assertNull(writerFailure.get());
                assertTrue(closed.get());
                assertFalse(cleanupBeforeClose.get());
            } finally {
                releaseWorkers.countDown();
            }
        }
    }

    @Test
    void deadlineSchedulerOnlyDispatchesBlockingCancellation() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch closeEntered = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1);
            CountDownLatch schedulerAdvanced = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofMillis(20),
                failure -> failure);
            execution.begin();
            execution.track(() -> {
                closeEntered.countDown();
                await(releaseClose);
            });
            executors.deadline().schedule(
                schedulerAdvanced::countDown,
                40,
                TimeUnit.MILLISECONDS);

            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(schedulerAdvanced.await(1, TimeUnit.SECONDS));
            releaseClose.countDown();
            execution.writerFinished(null);
        }
    }

    @Test
    void absoluteDeadlineCancelsANewExecutionWithoutServletParticipation() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch cleanup = new CountDownLatch(1);
            TenantExportExecution execution = executors.execution(
                Duration.ofMillis(20),
                failure -> {
                    cleanup.countDown();
                    return failure;
                });

            assertTrue(cleanup.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, execution::begin);
        }
    }

    @Test
    void asynchronousCleanupFailureLogContainsNoFailureMetadata() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            Logger logger = (Logger) LoggerFactory.getLogger(TenantExportExecution.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                CountDownLatch cleanup = new CountDownLatch(1);
                TenantExportExecution execution = executors.execution(
                    Duration.ofSeconds(5),
                    failure -> {
                        cleanup.countDown();
                        return new IOException(
                            "pii@example.com workspaces/9/private-object");
                    });

                execution.cancel();
                assertTrue(cleanup.await(1, TimeUnit.SECONDS));
                long logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (appender.list.isEmpty() && System.nanoTime() < logDeadline) {
                    Thread.onSpinWait();
                }

                assertEquals(1, appender.list.size());
                ILoggingEvent event = appender.list.getFirst();
                assertEquals("Tenant export cleanup failed", event.getFormattedMessage());
                assertNull(event.getThrowableProxy());
                assertFalse(event.getFormattedMessage().contains("pii@example.com"));
                assertFalse(event.getFormattedMessage().contains("private-object"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    @Test
    void deadlineMustBeArmedExactlyOnceBeforeWriting() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            CountDownLatch cleanup = new CountDownLatch(1);
            TenantExportExecution execution = new TenantExportExecution(
                Duration.ofSeconds(5),
                executors.cancellation(),
                failure -> {
                    cleanup.countDown();
                    return failure;
                });

            assertThrows(IllegalStateException.class, execution::begin);
            execution.armDeadline(executors.deadline());
            assertThrows(
                IllegalStateException.class,
                () -> execution.armDeadline(executors.deadline()));
            execution.cancel();
            assertTrue(cleanup.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void deadlineMayFireBeforeItsScheduledFutureIsPublished() throws Exception {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            ScheduledThreadPoolExecutor inlineDeadline =
                new InlineDeadlineExecutor();
            CountDownLatch cleanup = new CountDownLatch(1);
            try {
                TenantExportExecution execution = new TenantExportExecution(
                    Duration.ofSeconds(5),
                    executors.cancellation(),
                    failure -> {
                        cleanup.countDown();
                        return failure;
                    });

                execution.armDeadline(inlineDeadline);

                assertTrue(cleanup.await(1, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, execution::begin);
            } finally {
                inlineDeadline.shutdownNow();
            }
        }
    }

    @Test
    void shutdownRejectionLeavesCancellationFailClosedAndDoesNotRunCleanup() {
        try (ExecutionExecutors executors = new ExecutionExecutors()) {
            AtomicInteger cleanups = new AtomicInteger();
            TenantExportExecution execution = executors.execution(
                Duration.ofSeconds(5),
                failure -> {
                    cleanups.incrementAndGet();
                    return failure;
                });
            executors.cancellation().shutdownNow();

            execution.cancel();
            execution.cancel();

            assertEquals(0, cleanups.get());
            assertThrows(IllegalStateException.class, execution::begin);
        }
    }

    private static void await(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException exception) {
                if (latch.getCount() == 0) {
                    return;
                }
            }
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos) {
            if (thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Writer did not wait for cancellation ownership");
    }

    private static final class ExecutionExecutors implements AutoCloseable {
        private final ScheduledThreadPoolExecutor deadline =
            new ScheduledThreadPoolExecutor(1);
        private final ThreadPoolExecutor cancellation = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(4));

        private ExecutionExecutors() {
            deadline.setRemoveOnCancelPolicy(true);
            cancellation.prestartAllCoreThreads();
        }

        private ScheduledThreadPoolExecutor deadline() {
            return deadline;
        }

        private ThreadPoolExecutor cancellation() {
            return cancellation;
        }

        private TenantExportExecution execution(
                Duration timeout,
                TenantExportExecution.Cleanup cleanup) {
            TenantExportExecution execution =
                new TenantExportExecution(timeout, cancellation, cleanup);
            execution.armDeadline(deadline);
            return execution;
        }

        @Override
        public void close() {
            deadline.shutdownNow();
            cancellation.shutdownNow();
        }
    }

    private static final class InlineDeadlineExecutor extends ScheduledThreadPoolExecutor {
        private InlineDeadlineExecutor() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit) {
            command.run();
            return super.schedule(() -> {
            }, 1, TimeUnit.DAYS);
        }
    }
}
