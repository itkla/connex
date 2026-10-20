package ooo.klae.connex.backend.notifications;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard.Publication;

/** Rejects queued stale content and retains authority through the transport handler. */
@Component
@RequiredArgsConstructor
public class AiChatPrivacyOutboundInterceptor implements ExecutorChannelInterceptor {
    private static final String AUTHORITY_HEADER = AiChatPrivacyOutboundInterceptor.class.getName() + ".authority";
    private static final Publication UNGUARDED = () -> {};
    private final AiAssistantPrivacyPublicationGuard publicationGuard;
    private final ThreadLocal<Deque<Publication>> deliveries = ThreadLocal.withInitial(ArrayDeque::new);

    private record Authority(int organizationId, long generation) {}

    /** Uses internal broker headers, never native STOMP headers exposed to the browser. */
    public static MessageHeaders headers(int organizationId, long generation) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader(AUTHORITY_HEADER, new Authority(organizationId, generation));
        headers.setLeaveMutable(true);
        return headers.getMessageHeaders();
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        Object captured = message.getHeaders().get(AUTHORITY_HEADER);
        Publication publication = UNGUARDED;
        if (captured != null) {
            if (!(captured instanceof Authority authority)) {
                return null;
            }
            try {
                publication = publicationGuard.retainCurrent(authority.organizationId(), authority.generation());
            } catch (IOException | RuntimeException unavailable) {
                return null;
            }
        }
        deliveries.get().push(publication);
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception failure) {
        Deque<Publication> active = deliveries.get();
        try {
            active.pop().close();
        } finally {
            if (active.isEmpty()) {
                deliveries.remove();
            }
        }
    }
}
