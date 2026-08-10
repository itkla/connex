package ooo.klae.connex.backend.services;

import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Compatibility schedule producer for legacy and canonical workflow owners. It persists canonical
 * outbox targets for every cadence and invokes {@link RuleEngineService} directly only while the
 * canonical runtime deployment gate is disabled; per-workflow {@code runtime_owner} and the shared
 * claim service decide the executor. Remove the legacy enumeration and direct engine call once no
 * legacy-owned or unpaired rule remains. Toggle with {@code connex.rules.scheduling-enabled}.
 */
@Component
@RequiredArgsConstructor
public class RuleScheduler {

    private final RuleMapper ruleMapper;
    private final WorkflowMapper workflowMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final WorkflowTriggerIntake workflowTriggerIntake;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;
    private final RuleEngineService ruleEngineService;
    private final JobRunRecorder jobRunRecorder;

    private static final Logger log = LoggerFactory.getLogger(RuleScheduler.class);
    private static final String[] CADENCES = {"hourly", "daily", "weekly"};

    @Value("${connex.rules.scheduling-enabled:true}")
    private boolean schedulingEnabled;

    @Scheduled(
        fixedDelayString = "${connex.rules.evaluation-delay-ms:900000}",
        initialDelayString = "${connex.rules.initial-delay-ms:900000}")
    public void evaluate() {
        if (!schedulingEnabled) {
            return;
        }
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                evaluateCatalog(catalog);
            } catch (Exception e) {
                log.warn("Schedule sweep failed for catalog {}: {}", catalog == null ? "(default)" : catalog, e.getMessage());
            }
        }
    }

    /**
     * Enumerates and runs one catalog's schedule rules. The {@code rule} table
     * is org-data, so the enumeration must run inside the catalog being swept;
     * failures are isolated per catalog by the caller and per workspace here,
     * so one bad placement never starves the rest of the fleet.
     */
    private void evaluateCatalog(String catalog) {
        TreeSet<Integer> workspaceIds = tenantWorkScope.withCatalog(catalog, () -> {
            TreeSet<Integer> ids = new TreeSet<>(ruleMapper.workspaceIdsWithEnabledScheduleRules());
            ids.addAll(workflowMapper.workspaceIdsWithEnabledScheduleWorkflows());
            return ids;
        });
        for (int workspaceId : workspaceIds) {
            JobRunDetail detail = JobRunDetail.startedUtc();
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    int completedCadences = 0;
                    int failedCadences = 0;
                    for (String cadence : CADENCES) {
                        try {
                            WorkflowTriggerDispatch.ScheduleTick dispatch =
                                new WorkflowTriggerDispatch.ScheduleTick(
                                workspaceId,
                                cadence,
                                scheduleBucket(cadence));
                            workflowTriggerIntake.enqueue(dispatch);
                            if (!workflowRuntimeProperties.enabled()) {
                                ruleEngineService.runSchedule(dispatch);
                            }
                            completedCadences++;
                        } catch (Exception e) {
                            failedCadences++;
                            log.warn("Schedule evaluation failed for workspace {} cadence {}: {}", workspaceId, cadence, e.getMessage());
                        }
                    }
                    record(
                        workspaceId,
                        failedCadences == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                        new JobRunDetail(detail.startedAt(), Map.of(
                            "completedCadences", completedCadences,
                            "failedCadences", failedCadences)));
                });
            } catch (Exception e) {
                log.warn("Schedule evaluation skipped for workspace {}: {}", workspaceId, e.getMessage());
            }
        }
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(JobRunRecorder.RULE_SCHEDULER, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.RULE_SCHEDULER,
                exception.getClass().getSimpleName());
        }
    }

    private static String scheduleBucket(String cadence) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        return switch (cadence) {
            case "hourly" -> now.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH"));
            case "weekly" -> now.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
                + "W" + now.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            default -> now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        };
    }
}
