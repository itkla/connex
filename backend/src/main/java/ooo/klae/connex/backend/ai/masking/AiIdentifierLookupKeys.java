package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;

/** Versioned, derived lookup aliases; these keys never grant record or masking authority. */
public record AiIdentifierLookupKeys(String literal, String label) {
    /** Bump whenever canonicalization or dictionary eligibility changes, including JDK Unicode changes. */
    public static final int VERSION = 1;

    /** Retains explicit empty aliases for ineligible values, distinct from an unprocessed null bundle. */
    public AiIdentifierLookupKeys {
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(label, "label");
    }

    /**
     * Derives both stored-name projections through the same canonicalization and eligibility as admission.
     * An exhausted label projection retains literal coverage. A literal-ineligible name cannot be
     * admitted by the existing gate, even when its label alone would be eligible.
     * @param name authoritative source name
     * @return literal and label keys, with empty strings for unavailable aliases
     */
    public static AiIdentifierLookupKeys fromName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            return new AiIdentifierLookupKeys("", "");
        }
        CanonicalText.StoredIdentifier stored = CanonicalText.storedIdentifier(name);
        if (!MaskingContext.isCanonicalDictionaryEligible(stored.literal())) {
            return new AiIdentifierLookupKeys("", "");
        }
        return new AiIdentifierLookupKeys(stored.literal(),
                MaskingContext.isCanonicalDictionaryEligible(stored.label()) ? stored.label() : "");
    }
}
