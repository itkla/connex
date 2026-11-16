package ooo.klae.connex.backend.notifications;

import java.io.IOException;

import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard.Publication;
import ooo.klae.connex.backend.notifications.AiChatPrivacyOutboundInterceptor.Authority;

/** Carries per-frame authority through Spring's buffer and checks it only at the final socket write. */
public final class AiChatPrivacyWebSocketSession extends ConcurrentWebSocketSessionDecorator {
    private final AiChatPrivacyOutboundInterceptor interceptor;

    /** Preserves the broker's configured limits and default overflow behavior. */
    public AiChatPrivacyWebSocketSession(WebSocketSession delegate, int sendTimeLimit, int bufferSizeLimit,
            AiChatPrivacyOutboundInterceptor interceptor, AiAssistantPrivacyPublicationGuard guard) {
        super(new FinalWriteSession(delegate, guard), sendTimeLimit, bufferSizeLimit);
        this.interceptor = interceptor;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        Authority authority = interceptor.currentAuthority();
        super.sendMessage(authority == null ? message : new GuardedMessage(message, authority));
    }

    private record GuardedMessage(WebSocketMessage<?> original, Authority authority) implements WebSocketMessage<Object> {
        @Override
        public Object getPayload() {
            return original.getPayload();
        }

        @Override
        public int getPayloadLength() {
            return original.getPayloadLength();
        }

        @Override
        public boolean isLast() {
            return original.isLast();
        }
    }

    private static final class FinalWriteSession extends WebSocketSessionDecorator {
        private final AiAssistantPrivacyPublicationGuard guard;

        private FinalWriteSession(WebSocketSession delegate, AiAssistantPrivacyPublicationGuard guard) {
            super(delegate);
            this.guard = guard;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (!(message instanceof GuardedMessage guarded)) {
                super.sendMessage(message);
                return;
            }
            Publication publication;
            try {
                publication = guard.retainCurrent(guarded.authority().organizationId(), guarded.authority().generation());
            } catch (IOException | RuntimeException unavailable) {
                return;
            }
            try (publication) {
                super.sendMessage(guarded.original());
            }
        }
    }
}
