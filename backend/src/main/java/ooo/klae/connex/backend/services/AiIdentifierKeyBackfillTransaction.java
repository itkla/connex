package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.beans.AiIdentifierKeyBackfillRow;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;

/** Small, single-table, derived-only transactions; no domain events or record timestamps change. */
@Service
@RequiredArgsConstructor
public class AiIdentifierKeyBackfillTransaction {
    /** Bounds owned rows examined and row locks per transaction independently of freshness. */
    public static final int PAGE_SIZE = 100;

    private final AiAssistantIdentifierMapper mapper;

    /**
     * Examines one sorted owned-id window in the workspace's already pinned catalog. Compare-and-set
     * revalidates names and processing restrictions after any lock wait. Skipped races remain
     * stale and are retried on the next run; lookup keeps the compatibility path meanwhile.
     * @param workspaceId server-resolved owning workspace
     * @param kind one of person, company or deal
     * @param afterId exclusive immutable row cursor
     * @return page cursor and CAS outcomes
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Batch backfillPage(int workspaceId, String kind, int afterId) {
        if (workspaceId <= 0 || afterId < 0) {
            throw new IllegalArgumentException("Invalid identifier key backfill scope");
        }
        List<AiIdentifierKeyBackfillRow> rows = switch (kind) {
            case "person" -> mapper.findPersonKeyBackfillWindow(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            case "company" -> mapper.findCompanyKeyBackfillWindow(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            case "deal" -> mapper.findDealKeyBackfillWindow(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            default -> throw new IllegalArgumentException("Invalid identifier key backfill kind");
        };
        int cursor = afterId;
        int written = 0;
        int pending = 0;
        for (AiIdentifierKeyBackfillRow row : rows) {
            if (row.getId() <= cursor || !kind.equals(row.getKind())) {
                throw new IllegalStateException("Invalid identifier key backfill page");
            }
            cursor = row.getId();
            if (!row.isNeedsBackfill()) {
                continue;
            }
            if (row.getValue() == null) {
                throw new IllegalStateException("Missing identifier key backfill source");
            }
            pending++;
            written += switch (kind) {
                case "person" -> mapper.updatePersonKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                case "company" -> mapper.updateCompanyKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                case "deal" -> mapper.updateDealKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                default -> throw new IllegalArgumentException("Invalid identifier key backfill kind");
            };
        }
        return new Batch(cursor, rows.size(), written, pending - written);
    }

    /** Optional full-corpus diagnostic; scheduled pages accumulate observations instead of calling this. */
    public long pendingCount(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Invalid identifier key backfill scope");
        }
        return mapper.countPendingKeys(workspaceId, AiIdentifierLookupKeys.VERSION);
    }

    /** Counts all examined rows, successful repairs and pending CAS skips; contains no identifier text. */
    public record Batch(int lastId, int scanned, int written, int skipped) {
    }
}
