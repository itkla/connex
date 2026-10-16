package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.AiIdentifierLookupKeys;
import ooo.klae.connex.backend.beans.AiAssistantIdentifierMention;
import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;

/** Small, single-table, derived-only transactions; no domain events or record timestamps change. */
@Service
@RequiredArgsConstructor
public class AiIdentifierKeyBackfillTransaction {
    /** Bounds row locks per transaction independently of the visible corpus. */
    public static final int PAGE_SIZE = 100;

    private final AiAssistantIdentifierMapper mapper;

    /**
     * Repairs one sorted keyset page in the workspace's already pinned catalog. Compare-and-set
     * revalidates names and processing restrictions after any lock wait. Skipped races remain
     * stale and are retried on the next run; lookup keeps the compatibility path meanwhile.
     * @param workspaceId server-resolved owning workspace
     * @param kind one of person, company or deal
     * @param afterId exclusive immutable row cursor
     * @return page cursor and CAS outcomes
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Batch backfillPage(int workspaceId, String kind, int afterId) {
        if (workspaceId <= 0 || afterId < 0) {
            throw new IllegalArgumentException("Invalid identifier key backfill scope");
        }
        List<AiAssistantIdentifierMention> rows = switch (kind) {
            case "person" -> mapper.findPersonKeysToBackfill(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            case "company" -> mapper.findCompanyKeysToBackfill(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            case "deal" -> mapper.findDealKeysToBackfill(workspaceId, afterId, PAGE_SIZE, AiIdentifierLookupKeys.VERSION);
            default -> throw new IllegalArgumentException("Invalid identifier key backfill kind");
        };
        int cursor = afterId;
        int written = 0;
        for (AiAssistantIdentifierMention row : rows) {
            if (row.getId() <= cursor || !kind.equals(row.getKind()) || row.getValue() == null) {
                throw new IllegalStateException("Invalid identifier key backfill page");
            }
            cursor = row.getId();
            written += switch (kind) {
                case "person" -> mapper.updatePersonKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                case "company" -> mapper.updateCompanyKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                case "deal" -> mapper.updateDealKeysIfNameCurrent(workspaceId, cursor, row.getValue(), AiIdentifierLookupKeys.VERSION);
                default -> throw new IllegalArgumentException("Invalid identifier key backfill kind");
            };
        }
        return new Batch(cursor, rows.size(), written, rows.size() - written);
    }

    /** Returns a fresh pending count in the pinned catalog, including races behind the cursor. */
    public long pendingCount(int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Invalid identifier key backfill scope");
        }
        return mapper.countPendingKeys(workspaceId, AiIdentifierLookupKeys.VERSION);
    }

    /** Cursor and counts for a committed or rolled-back page; contains no identifier text. */
    public record Batch(int lastId, int scanned, int written, int skipped) {
    }
}
