package ooo.klae.connex.backend.ai.mission;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;

/** Reconciles abandoned attempts before opening mission admission after deployment. */
@Service
@RequiredArgsConstructor
public class AiAgentStartupRecoveryService {
    private final AiAgentRunMapper runs;
    private final AiAgentRunPersistenceService persistence;
    private final AiRunLeaseMapper leases;

    /** Refuses inconsistent active linkage and settles a bounded batch of unowned attempts. */
    public void reconcile(int workspaceId) {
        if (runs.countInconsistentActiveRuns(workspaceId) != 0) {
            throw new IllegalStateException("Mission active attempt linkage requires repair");
        }
        for (var lease : leases.findExpiredLeases(workspaceId, List.of("agent_run"), 50)) {
            var orphan = runs.getRun(workspaceId, Math.toIntExact(lease.getSubjectId()));
            if (orphan != null && "running".equals(orphan.getStatus())) {
                persistence.settleOrphan(orphan, new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.AGENT_RUN,
                        lease.getSubjectId()), lease.getEpoch());
            }
        }
        if (!runs.orphanedRuns(workspaceId, 1).isEmpty()) {
            throw new IllegalStateException("Mission startup reconciliation is incomplete");
        }
    }
}
