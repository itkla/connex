package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PlatformTransactionManager transactionManager;

    /** Refuses inconsistent active linkage and settles a bounded batch of unowned attempts. */
    public void reconcile(int workspaceId) {
        if (read(() -> runs.countInconsistentActiveRuns(workspaceId)) != 0) {
            throw new IllegalStateException("Mission active attempt linkage requires repair");
        }
        for (var lease : read(() -> leases.findExpiredLeases(workspaceId, List.of("agent_run"), 50))) {
            var orphan = read(() -> runs.getRun(workspaceId, Math.toIntExact(lease.getSubjectId())));
            if (orphan != null && "running".equals(orphan.getStatus())) {
                persistence.settleOrphan(orphan, new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.AGENT_RUN,
                        lease.getSubjectId()), lease.getEpoch());
            }
        }
        if (!read(() -> runs.orphanedRuns(workspaceId, 1)).isEmpty()) {
            throw new IllegalStateException("Mission startup reconciliation is incomplete");
        }
        var candidates = new java.util.ArrayList<>(read(() -> runs.dueRuns(workspaceId, 25)));
        candidates.addAll(read(() -> runs.interruptedReadRuns(workspaceId, 25)));
        for (var run : candidates) {
            var codec = new AiAgentContinuationCodec();
            var snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
            }
        }
    }

    /** Ends each routed snapshot before settlement so one run never retains a lease ahead of the next run's roots. */
    private <T> T read(Supplier<T> work) {
        var read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read.setReadOnly(true);
        return read.execute(status -> work.get());
    }
}
