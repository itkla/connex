package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Interaction-history preview counts, row decisions, and one-use commit proof.
 *
 * @param total source row count
 * @param toCreate rows that will be inserted
 * @param alreadyImported idempotent replay rows
 * @param needsReview rows without a safe participant decision
 * @param invalid rows with invalid data or provenance collisions
 * @param rows source-ordered decisions
 * @param duplicateReviewProof one-use proof binding the rendered preview
 */
public record HistoryImportPreviewResult(
        int total,
        int toCreate,
        int alreadyImported,
        int needsReview,
        int invalid,
        List<HistoryImportRowAnalysis> rows,
        String duplicateReviewProof) {

    public HistoryImportPreviewResult {
        rows = List.copyOf(rows);
    }
}
