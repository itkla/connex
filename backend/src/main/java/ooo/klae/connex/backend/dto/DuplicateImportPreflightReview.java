package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Ordered import duplicate results plus the one-use proof for this rendered review.
 *
 * @param responses one response per eligible import row
 * @param reviewProof opaque one-use proof bound to these ordered results
 */
public record DuplicateImportPreflightReview(
        List<DuplicatePreflightResponse> responses,
        String reviewProof) {

    public DuplicateImportPreflightReview {
        responses = List.copyOf(responses);
    }
}
