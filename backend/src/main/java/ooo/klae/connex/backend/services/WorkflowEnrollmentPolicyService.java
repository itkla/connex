package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.WorkflowEnrollment;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Evaluates schema-v2 entry and repeat policy from one current record snapshot. */
@Service
@RequiredArgsConstructor
public class WorkflowEnrollmentPolicyService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowRecordPolicyService recordPolicyService;
    private final WorkflowRecordGuard recordGuard;
    private final SegmentService segmentService;

    public Decision evaluateLocked(
            WorkflowRun run, CompiledWorkflow compiled, int attributionUserId) {
        if (!recordPolicyService.entryMatched(run, compiled, attributionUserId)) {
            return new Decision("entry_condition_not_matched", null);
        }
        return repeatPolicy(run, compiled, databaseNow(run));
    }

    public Decision preview(
            WorkflowRun run, CompiledWorkflow compiled, int attributionUserId) {
        recordGuard.requireAccessible(run);
        WorkflowEnrollment enrollment = compiled.enrollment();
        if (enrollment != null && enrollment.condition() != null
                && !segmentService.matchesEntity(
                    run.getWorkspaceId(), attributionUserId, run.getRecordType(),
                    enrollment.condition(), run.getRecordId())) {
            return new Decision("entry_condition_not_matched", null);
        }
        return repeatPolicy(run, compiled, databaseNow(run));
    }

    private Decision repeatPolicy(
            WorkflowRun run, CompiledWorkflow compiled, LocalDateTime now) {
        WorkflowEnrollment enrollment = compiled.enrollment();
        if (enrollment == null) {
            return Decision.allowedDecision();
        }
        if (Boolean.TRUE.equals(enrollment.oneActiveRun())
                && runMapper.hasActiveEnrollment(
                    run.getWorkspaceId(), run.getWorkflowId(),
                    run.getRecordType(), run.getRecordId())) {
            return new Decision("active_run_exists", null);
        }
        int cooldownMinutes = enrollment.cooldownMinutes() == null
            ? 0 : enrollment.cooldownMinutes();
        if (cooldownMinutes > 0) {
            LocalDateTime latest = runMapper.latestEnrollmentStartedAt(
                run.getWorkspaceId(), run.getWorkflowId(),
                run.getRecordType(), run.getRecordId());
            LocalDateTime eligibleAt = latest == null
                ? null : latest.plusMinutes(cooldownMinutes);
            if (eligibleAt != null && eligibleAt.isAfter(now)) {
                return new Decision("cooldown_active", eligibleAt);
            }
        }
        return Decision.allowedDecision();
    }

    private LocalDateTime databaseNow(WorkflowRun run) {
        LocalDateTime now = runMapper.currentTimestamp(
            run.getWorkspaceId(), run.getWorkflowId());
        if (now == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The workflow is unavailable.",
                true);
        }
        return now;
    }

    /** One stable suppression reason and its optional cooldown eligibility timestamp. */
    public record Decision(String reason, LocalDateTime eligibleAt) {

        public boolean allowed() {
            return reason == null;
        }

        static Decision allowedDecision() {
            return new Decision(null, null);
        }
    }
}
