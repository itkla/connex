package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Placement-aware reconciliation that never invents a replacement privacy generation. */
@Component
@RequiredArgsConstructor
public class AiAgentPrivacyInitializer {
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final AiAgentPrivacyService privacyService;
    private final AiAgentStartupRecoveryService startupRecovery;
    private final JobRunRecorder jobRunRecorder;
    private final AtomicBoolean ready = new AtomicBoolean();

    /** Signals a committed control-plane workspace whose tenant barrier can now be initialized. */
    public record WorkspaceProvisioned(int workspaceId) {}

    /** Exposes fail-closed initialization readiness to mission admission. */
    public boolean isReady() {
        return ready.get();
    }

    /** Reconciles existing workspaces only after the application migration lifecycle has completed. */
    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        reconcile();
    }

    /** Recovers provisioning crashes and initialization failures without depending on AI enablement. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-privacy-reconcile-delay:60s}", initialDelay = 60000)
    public synchronized void reconcile() {
        int after = 0;
        boolean successful = true;
        while (true) {
            int cursor = after;
            List<Integer> ids = tenantWorkScope.unroutedRead(() -> workspaceMapper.findWorkspaceIdsPage(cursor, 100));
            if (ids.isEmpty()) {
                ready.set(successful);
                return;
            }
            for (int workspaceId : ids) {
                try {
                    initialize(workspaceId);
                } catch (RuntimeException failure) {
                    successful = false;
                }
            }
            after = ids.getLast();
        }
    }

    /** Initializes one exact workspace using its freshly verified organization placement. */
    public void initialize(int workspaceId) {
        tenantWorkScope.inWorkspace(workspaceId, () -> {
            JobRunDetail started = JobRunDetail.startedUtc();
            JobRunStatus outcome = JobRunStatus.SUCCEEDED;
            try {
                privacyService.initialize(workspaceId);
                privacyService.reconcileLegacyTurns(workspaceId);
                startupRecovery.reconcile(workspaceId);
                return null;
            } catch (RuntimeException failure) {
                outcome = JobRunStatus.FAILED;
                throw failure;
            } finally {
                jobRunRecorder.record(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION, workspaceId,
                        outcome, new JobRunDetail(started.startedAt(), Map.of("phase", "initialization")));
            }
        });
    }

    /** Runs after both self-service and bootstrap provisioning commit on the control plane. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void provisioned(WorkspaceProvisioned event) {
        try {
            initialize(event.workspaceId());
        } catch (RuntimeException failure) {
            ready.set(false);
        }
    }
}
