package ooo.klae.connex.backend.dto;

/**
 * Display-safe evidence metadata for a duplicate-review item.
 *
 * @param kind matched canonical field kind; the canonical value remains server-side
 */
public record DuplicateReviewEvidenceDto(DuplicateMatchKind kind) {
}
