package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Per-row catalog preview decision. The status is the decided action: the conflict policy and any
 * per-row override are already applied, so no ambiguity is deferred to the commit.
 *
 * @param rowIndex zero-based source row index
 * @param status create, update, skip, or invalid
 * @param sku trimmed source SKU, or null when the row supplied none
 * @param matchedId existing catalog row that owns this SKU
 * @param matchedLabel existing catalog row name
 * @param errors validation reasons
 */
public record ProductImportRowAnalysis(
        int rowIndex,
        String status,
        String sku,
        Integer matchedId,
        String matchedLabel,
        List<String> errors) {

    public ProductImportRowAnalysis {
        errors = List.copyOf(errors);
    }
}
