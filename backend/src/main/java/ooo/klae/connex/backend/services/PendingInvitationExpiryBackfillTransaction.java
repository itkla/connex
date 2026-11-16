package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Persists legacy deadlines with bounded point locks while the read fallback stays authoritative. */
@Service
@RequiredArgsConstructor
public class PendingInvitationExpiryBackfillTransaction {
    public static final int PAGE_SIZE = 100;
    private final WorkspaceMapper mapper;

    /** Commits one workspace primary-key window independently; active rows are only read. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public Batch backfillPage(int workspaceId, int afterUserId) {
        if (workspaceId <= 0 || afterUserId < 0) {
            throw new IllegalArgumentException("Invalid invitation expiry backfill scope");
        }
        var rows = mapper.findPendingDeadlineWindow(workspaceId, afterUserId, PAGE_SIZE);
        int cursor = afterUserId;
        int written = 0;
        for (var row : rows) {
            if (row.userId() <= cursor || row.membershipId() <= 0) {
                throw new IllegalStateException("Invalid invitation expiry backfill page");
            }
            cursor = row.userId();
            if (row.needsBackfill()) {
                written += mapper.backfillPendingDeadline(workspaceId, cursor, row.membershipId());
            }
        }
        return new Batch(cursor, rows.size(), written);
    }

    /** Contains only pagination and repair counts. */
    public record Batch(int lastUserId, int scanned, int written) { }
}
