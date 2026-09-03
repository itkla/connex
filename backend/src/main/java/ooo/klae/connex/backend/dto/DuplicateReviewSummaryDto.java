package ooo.klae.connex.backend.dto;

/**
 * Current open duplicate-family review counts.
 *
 * @param personOpenCount open person pair or oversized-group items
 * @param companyOpenCount open company pair or oversized-group items
 */
public record DuplicateReviewSummaryDto(
        long personOpenCount,
        long companyOpenCount) {
}
