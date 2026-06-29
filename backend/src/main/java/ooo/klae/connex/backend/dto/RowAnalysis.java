package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-row outcome of a dry-run import analysis: {@code status} is one of "create", "match",
 * "skip", or "invalid"; {@code matchedId}/{@code matchedLabel} identify an existing record when
 * matched; {@code errors} lists validation problems for the row.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RowAnalysis {
    private int rowIndex;
    private String status;
    private Integer matchedId;
    private String matchedLabel;
    private List<String> errors;
}
