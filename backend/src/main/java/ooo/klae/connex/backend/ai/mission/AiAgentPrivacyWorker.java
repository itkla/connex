package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Bounded privacy maintenance independent of providers, AI flags, and departed requesters. */
@Component
@RequiredArgsConstructor
public class AiAgentPrivacyWorker {
    private final WorkspaceMapper workspaceMapper;
    private final AiAgentPrivacyMapper privacyMapper;
    private final AiAgentPrivacyService privacyService;
    private final AiAgentDepartureMaintenanceService departures;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final AtomicInteger workspaceCursor = new AtomicInteger();

    /** Visits at most twenty workspaces and twenty batches on each pass. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-privacy-work-delay:1s}", initialDelay = 10000)
    public void sweep() {
        List<Integer> workspaces = tenantWorkScope.unroutedRead(
                () -> workspaceMapper.findWorkspaceIdsLifecyclePage(workspaceCursor.get(), 20));
        if (workspaces.isEmpty()) {
            workspaceCursor.set(0);
            return;
        }
        for (int workspaceId : workspaces) {
            JobRunDetail started = JobRunDetail.startedUtc();
            boolean failed = false;
            try {
                tenantWorkScope.inLifecycleWorkspace(workspaceId, () -> {
                    departures.reconcile(workspaceId);
                    privacyService.reconcileErasedOwners(workspaceId);
                    List<AiAgentPrivacyWork> work = privacyMapper.dueWork(workspaceId, 1);
                    if (!work.isEmpty()) {
                        privacyService.batch(workspaceId, work.getFirst().getOperationKey());
                    }
                });
            } catch (RuntimeException failure) {
                failed = true;
            } finally {
                jobRunRecorder.record(JobRunRecorder.AI_AGENT_PRIVACY_CLEANUP, workspaceId,
                        failed ? JobRunStatus.FAILED : JobRunStatus.SUCCEEDED,
                        new JobRunDetail(started.startedAt(), Map.of("phase", "cleanup")));
                workspaceCursor.set(workspaceId);
            }
        }
    }
}
