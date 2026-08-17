package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Committed catalog import outcome.
 *
 * @param created catalog rows inserted
 * @param updated existing catalog rows overwritten in place
 * @param skipped rows left untouched by the conflict policy or an explicit override
 * @param failed rows that could not be imported
 */
public record ProductImportResult(
        int created,
        int updated,
        int skipped,
        List<RowError> failed) {

    public ProductImportResult {
        failed = List.copyOf(failed);
    }
}
