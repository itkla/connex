package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.Set;

import lombok.Getter;

/**
 * Message content that has passed through the masking boundary.
 */
@Getter
public final class MaskedMessage {
    /** Role discriminator for a masked end-user turn. */
    static final String ROLE_USER = "user";

    /** Role discriminator for a masked assistant turn. */
    static final String ROLE_ASSISTANT = "assistant";

    /**
     * The closed role vocabulary, enforced by the constructor so that the discriminator a provider
     * envelope carries is provably server-authored rather than tenant text.
     */
    static final Set<String> ROLES = Set.of(ROLE_USER, ROLE_ASSISTANT);

    private final String role;
    private final String content;

    MaskedMessage(String role, String content) {
        if (!ROLES.contains(Objects.requireNonNull(role, "role"))) {
            throw new IllegalArgumentException("Masked message role is invalid");
        }
        this.role = role;
        this.content = Objects.requireNonNull(content, "content");
    }
}
