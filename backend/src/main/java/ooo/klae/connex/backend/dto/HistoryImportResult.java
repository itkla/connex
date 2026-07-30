package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Committed interaction-history import outcome.
 *
 * @param created rows inserted
 * @param skipped idempotent replay rows
 * @param failed rows that remained invalid or unresolved
 */
public record HistoryImportResult(
        int created,
        int skipped,
        List<RowError> failed) {

    public HistoryImportResult {
        failed = List.copyOf(failed);
    }
}
