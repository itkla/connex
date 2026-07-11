package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Single chat message accepted by a provider adapter.
 * @param role provider role, currently {@code user} or {@code assistant}
 * @param content message text
 */
public record AiMessage(String role, String content) {

    public AiMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    @Override
    public String toString() {
        return "AiMessage[role=" + role + ", content=<redacted>]";
    }
}
