package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;

import lombok.Getter;

/**
 * Message content that has passed through the masking boundary.
 */
@Getter
public final class MaskedMessage {
    private final String role;
    private final String content;

    MaskedMessage(String role, String content) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = Objects.requireNonNull(content, "content");
    }
}
