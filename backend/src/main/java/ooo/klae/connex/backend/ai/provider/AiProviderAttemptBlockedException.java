package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/** Internal signal that a provider attempt was blocked before transport egress. */
public class AiProviderAttemptBlockedException extends AiProviderException {
    /** Stable provider-attempt admission reason. */
    public enum Reason {
        ORGANIZATION_QUOTA,
        CAPACITY,
        RESTRICTION_EPOCH
    }

    private final Reason reason;

    public AiProviderAttemptBlockedException(Reason reason) {
        super("AI provider attempt was blocked");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** @return stable content-free block reason */
    public Reason reason() {
        return reason;
    }
}
