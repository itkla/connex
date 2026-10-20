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

/** Rejects queued stale content and supplies immutable authority to the encoded transport buffer. */
@Component
@RequiredArgsConstructor
public class AiChatPrivacyOutboundInterceptor implements ExecutorChannelInterceptor {
    private static final String AUTHORITY_HEADER = AiChatPrivacyOutboundInterceptor.class.getName() + ".authority";
    private final AiAssistantPrivacyPublicationGuard publicationGuard;
    private final ThreadLocal<Deque<Authority>> deliveries = new ThreadLocal<>();

    record Authority(int organizationId, long generation) {}
    private static final Authority UNGUARDED = new Authority(0, 0);

    Authority currentAuthority() {
        Deque<Authority> active = deliveries.get();
        return active == null || active.isEmpty() || active.peek() == UNGUARDED ? null : active.peek();
    }

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
        Authority provenance = UNGUARDED;
        if (captured != null) {
            if (!(captured instanceof Authority authority)) {
                return null;
            }
            try (Publication publication = publicationGuard.retainCurrent(authority.organizationId(), authority.generation())) {
                provenance = authority;
            } catch (IOException | RuntimeException unavailable) {
                return null;
            }
        }
        Deque<Authority> active = deliveries.get();
        if (active == null) {
            active = new ArrayDeque<>();
            deliveries.set(active);
        }
        active.push(provenance);
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception failure) {
        Deque<Authority> active = deliveries.get();
        active.pop();
        if (active.isEmpty()) {
            deliveries.remove();
        }
    }
}
