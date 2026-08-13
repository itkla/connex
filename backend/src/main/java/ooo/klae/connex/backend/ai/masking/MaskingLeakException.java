package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;
import java.util.Set;

/**
 * Raised when the final outbound AI payload still contains a raw identifier from the request-local
 * masking context.
 */
public class MaskingLeakException extends RuntimeException {
    private static final String DEFAULT_MESSAGE = "Outbound AI payload contains an unmasked identifier";

    private final Set<EntityKind> leakedKinds;
    private final int leakedCount;

    public MaskingLeakException(String message) {
        super(message);
        leakedKinds = Set.of();
        leakedCount = 0;
    }

    MaskingLeakException(Set<EntityKind> leakedKinds, int leakedCount) {
        super(DEFAULT_MESSAGE);
        this.leakedKinds = Set.copyOf(Objects.requireNonNull(leakedKinds, "leakedKinds"));
        if (this.leakedKinds.isEmpty() || leakedCount < 1) {
            throw new IllegalArgumentException("Leak diagnostics require a positive binding count and kind");
        }
        this.leakedCount = leakedCount;
    }

    /**
     * Returns the entity namespaces of the leaked bindings without exposing their values or tokens.
     * @return immutable leaked entity-kind set
     */
    public Set<EntityKind> leakedKinds() {
        return leakedKinds;
    }

    /**
     * Returns the number of distinct leaked bindings, not the number of plaintext occurrences.
     * @return distinct leaked binding count
     */
    public int leakedCount() {
        return leakedCount;
    }
}
