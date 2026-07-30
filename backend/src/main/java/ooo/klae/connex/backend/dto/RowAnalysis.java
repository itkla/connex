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
 * {@code candidates} carries bounded duplicate-preflight suggestions for explicit review.
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
}
