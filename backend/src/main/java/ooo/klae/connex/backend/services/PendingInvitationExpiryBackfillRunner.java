package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Repairs at most four independently committed pages per sweep without gating startup or expiry. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connex.maintenance", name = "mode", havingValue = "off", matchIfMissing = true)
public class PendingInvitationExpiryBackfillRunner {
    private static final Logger log = LoggerFactory.getLogger(PendingInvitationExpiryBackfillRunner.class);
    private static final int PAGES_PER_SWEEP = 4;
    private final WorkspaceMapper workspaces;
    private final TenantWorkScope scope;
    private final PendingInvitationExpiryBackfillTransaction transaction;
    private int workspaceAfterId;
    private int workspaceId;
    private int afterUserId;

    /** Routes before each transaction and advances the cursor only after that page commits. */
    @Scheduled(fixedDelayString = "${connex.invitation-expiry.backfill-delay-ms:30000}",
        initialDelayString = "${connex.invitation-expiry.backfill-initial-delay-ms:30000}")
    public synchronized void reconcile() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Invitation expiry reconciliation requires no ambient transaction");
        }
        try {
            for (int page = 0; page < PAGES_PER_SWEEP; page++) {
                if (workspaceId == 0) {
                    var ids = scope.unroutedRead(() -> workspaces.findWorkspaceIdsPage(workspaceAfterId, 1));
                    if (ids.isEmpty()) {
                        workspaceAfterId = 0;
                        return;
                    }
                    workspaceId = ids.getFirst();
                    if (workspaceId <= workspaceAfterId) {
                        throw new IllegalStateException("Invitation expiry workspace cursor did not advance");
                    }
                    workspaceAfterId = workspaceId;
                }
                var batch = scope.unrouted(() -> transaction.backfillPage(workspaceId, afterUserId));
                afterUserId = batch.lastUserId();
                if (batch.scanned() < PendingInvitationExpiryBackfillTransaction.PAGE_SIZE) {
                    workspaceId = 0;
                    afterUserId = 0;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Invitation expiry backfill failed exceptionClass={}; fixed deadline fallback remains active",
                exception.getClass().getSimpleName());
        }
    }
}
