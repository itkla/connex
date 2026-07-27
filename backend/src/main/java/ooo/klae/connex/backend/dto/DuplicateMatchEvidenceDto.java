package ooo.klae.connex.backend.dto;

/**
 * One exact field match contributing to a duplicate candidate.
 *
 * @param kind matched field kind
 * @param normalizedValue canonical value that matched
 * @param strength explicit confidence tier
 */
public record DuplicateMatchEvidenceDto(
        DuplicateMatchKind kind,
        String normalizedValue,
        DuplicateMatchStrength strength) {
}
