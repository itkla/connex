package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Dry-run summary of an import: aggregate counts plus the per-row analysis backing the review step. */
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
}
