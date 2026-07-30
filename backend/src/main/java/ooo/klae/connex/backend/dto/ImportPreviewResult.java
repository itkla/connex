package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dry-run summary of an import: aggregate counts, per-row analysis, and its one-use commit proof.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportPreviewResult {
    private int total;
    private int toCreate;
    private int toUpdate;
    private int toSkip;
    private int invalid;
    private List<RowAnalysis> rows;
    private String duplicateReviewProof;

    public ImportPreviewResult(
            int total,
            int toCreate,
            int toUpdate,
            int toSkip,
            int invalid,
            List<RowAnalysis> rows) {
        this(total, toCreate, toUpdate, toSkip, invalid, rows, null);
    }
}
