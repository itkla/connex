package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dry-run summary of an import: aggregate counts, per-row analysis, and its one-use commit proof.
 */
@Data
@NoArgsConstructor
public class ImportPreviewResult {
    private int total;
    private int toCreate;
    private int toUpdate;
    private int toSkip;
    private int invalid;
    private List<RowAnalysis> rows;
    private String duplicateReviewProof;
    private Map<String, List<DuplicateCandidateDto>> companyCandidates;
    private String defaultCurrency;

    public ImportPreviewResult(
            int total, int toCreate, int toUpdate, int toSkip, int invalid,
            List<RowAnalysis> rows, String duplicateReviewProof,
            Map<String, List<DuplicateCandidateDto>> companyCandidates) {
        this.total = total;
        this.toCreate = toCreate;
        this.toUpdate = toUpdate;
        this.toSkip = toSkip;
        this.invalid = invalid;
        this.rows = rows;
        this.duplicateReviewProof = duplicateReviewProof;
        this.companyCandidates = companyCandidates;
    }

    public ImportPreviewResult(
            int total, int toCreate, int toUpdate, int toSkip, int invalid,
            List<RowAnalysis> rows, String duplicateReviewProof) {
        this(total, toCreate, toUpdate, toSkip, invalid, rows, duplicateReviewProof, Map.of());
    }

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
