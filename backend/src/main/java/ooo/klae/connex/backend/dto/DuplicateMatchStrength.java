package ooo.klae.connex.backend.dto;

/**
 * Duplicate-preflight confidence tier.
 *
 * <p>{@link #STRONG} means an exact canonical identity-key match. {@link #WEAK} means only an
 * exact normalized-name match. No fuzzy or edit-distance tier exists.
 */
public enum DuplicateMatchStrength {
    STRONG,
    WEAK
}
