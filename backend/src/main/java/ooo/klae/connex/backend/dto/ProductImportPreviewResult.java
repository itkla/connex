package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Catalog preview counts, row decisions, and one-use commit proof.
 *
 * @param total source row count
 * @param toCreate rows that will be inserted
 * @param toUpdate rows that will overwrite an existing SKU
 * @param toSkip rows left untouched by the conflict policy or an explicit override
 * @param invalid rows with invalid data or an impossible decision
 * @param rows source-ordered decisions
 * @param duplicateReviewProof one-use proof binding the rendered preview
 */
public record ProductImportPreviewResult(
        int total,
        int toCreate,
        int toUpdate,
        int toSkip,
        int invalid,
        List<ProductImportRowAnalysis> rows,
        String duplicateReviewProof) {

    public ProductImportPreviewResult {
        rows = List.copyOf(rows);
    }
}
