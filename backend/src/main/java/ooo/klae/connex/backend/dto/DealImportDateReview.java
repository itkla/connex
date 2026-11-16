package ooo.klae.connex.backend.dto;

/** Effective import dates as ISO instants, with non-blocking warnings and their reporting timezone. */
public record DealImportDateReview(
        String closedAt,
        String createdAt,
        String timezone,
        boolean usesImportTime,
        boolean futureClosedAt) {
}
