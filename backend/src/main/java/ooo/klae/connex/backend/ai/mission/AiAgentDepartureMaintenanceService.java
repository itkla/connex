package ooo.klae.connex.backend.ai.mission;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Settles departed owners without erasing other members' workspace mission holdings. */
@Service
@RequiredArgsConstructor
public class AiAgentDepartureMaintenanceService {
    private final AiAgentRunMapper runs;
    private final org.springframework.beans.factory.ObjectProvider<AiAgentRunPersistenceService> persistence;
    private final WorkspaceMapper workspaces;
    private final TenantWorkScope workScope;
    private final PlatformTransactionManager transactions;
    private final ConcurrentMap<Integer, Integer> cursors = new ConcurrentHashMap<>();

    /** Carries only immutable identities after the membership-removal transaction commits. */
    public record Departure(int workspaceId, int ownerUserId) {}

    /**
     * Called while the existing absent-membership lock prevents mission creation. A fresh read
     * prevents an older control transaction snapshot from reviving unsettled pre-departure runs.
     */
    public void requireSettledBeforeFreshMembership(int workspaceId, int ownerUserId) {
        TransactionTemplate snapshot = new TransactionTemplate(transactions);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot.setReadOnly(true);
        Integer unsettled = snapshot.execute(status -> runs.countNonterminalForOwner(workspaceId, ownerUserId));
        if (unsettled == null || unsettled != 0) {
            throw new ConflictException("mission_departure_cleanup_pending");
        }
    }

    /** Cancels one bounded owner page immediately; the periodic sweep repairs a missed event. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void departed(Departure event) {
        try {
            workScope.inLifecycleWorkspace(event.workspaceId(), () -> {
                for (int id : runs.ownerRunIds(event.workspaceId(), event.ownerUserId(), 0, 100)) {
                    var run = runs.getRun(event.workspaceId(), id);
                    if (run != null && !workScope.unroutedRead(
                            () -> workspaces.isMember(event.workspaceId(), event.ownerUserId()))) {
                        cancel(run);
                    }
                }
            });
        } catch (RuntimeException unavailable) {
            cursors.remove(event.workspaceId());
        }
    }

    /** Visits at most one hundred roots using restartable discovery and current membership proof. */
    public void reconcile(int workspaceId) {
        var discovered = runs.nonterminalRunsAfter(workspaceId, cursors.getOrDefault(workspaceId, 0), 100);
        if (discovered.isEmpty()) {
            cursors.remove(workspaceId);
            return;
        }
        for (var run : discovered) {
            Integer owner = run.getOwnerUserId();
            boolean current = owner != null && workScope.unroutedRead(() -> workspaces.isMember(workspaceId, owner));
            if (!current) {
                cancel(run);
            }
        }
        cursors.put(workspaceId, discovered.getLast().getId());
    }

    private void cancel(ooo.klae.connex.backend.beans.AiAgentRun run) {
        TransactionTemplate settlement = new TransactionTemplate(transactions);
        settlement.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        settlement.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        settlement.executeWithoutResult(status -> persistence.getObject().cancelForDeparture(run));
    }
}
