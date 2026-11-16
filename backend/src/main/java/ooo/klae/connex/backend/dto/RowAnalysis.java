package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-row outcome of a dry-run import analysis: {@code status} is one of "create", "match",
 * "skip", or "invalid"; {@code matchedId}/{@code matchedLabel} identify an existing record when
 * matched; {@code canonicalRowIndex}/{@code mergedRowCount} identify rows that will be coalesced
 * into one canonical mutation; {@code errors} lists validation problems for the row;
 * {@code candidates} carries bounded duplicate-preflight suggestions for explicit review;
 * {@code dealDates} describes the effective dates and warnings for a writable deal row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RowAnalysis {
    private int rowIndex;
    private String status;
    private Integer matchedId;
    private String matchedLabel;
    private Integer canonicalRowIndex;
    private Integer mergedRowCount;
    private List<String> errors;
    private List<DuplicateCandidateDto> candidates;
    private DealImportDateReview dealDates;
    private ImportCompanyDependency companyDependency;

    public RowAnalysis(
            int rowIndex, String status, Integer matchedId, String matchedLabel,
            Integer canonicalRowIndex, Integer mergedRowCount, List<String> errors,
            List<DuplicateCandidateDto> candidates, DealImportDateReview dealDates) {
        this(rowIndex, status, matchedId, matchedLabel, canonicalRowIndex, mergedRowCount,
            errors, candidates, dealDates, null);
    }

    /** Localizable reasons; errors remains a diagnostic compatibility field. */
    public List<ImportProblem> getProblems() {
        return errors == null ? List.of() : errors.stream().map(ImportProblem::fromDiagnostic).toList();
    }

    public RowAnalysis(
            int rowIndex, String status, Integer matchedId, String matchedLabel,
            Integer canonicalRowIndex, Integer mergedRowCount, List<String> errors,
            List<DuplicateCandidateDto> candidates) {
        this(rowIndex, status, matchedId, matchedLabel, canonicalRowIndex, mergedRowCount,
            errors, candidates, null);
    }
}
