package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowRuntimeClaimService.CanonicalClaim;
import ooo.klae.connex.backend.services.WorkflowRuntimeClaimService.ScheduleEnrollment;

/** Dispatches each trigger through the persisted legacy-or-canonical ownership predicate. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeService.class);
    private static final int MAX_SCHEDULE_ENROLLMENTS = 128;

    private final WorkflowRuntimeProperties properties;
    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowRuntimeClaimService claimService;
    private final WorkflowTraversalService traversalService;
    private final WorkflowExecutionPrincipalService principalService;
    private final SegmentService segmentService;
    private final RuleEngineService ruleEngineService;
    private final WorkflowTriggeredSendGate triggeredSendGate;
    private final AuditService auditService;

    public WorkflowDispatchResult dispatch(WorkflowTriggerDispatch dispatch) {
        if (dispatch instanceof WorkflowTriggerDispatch.EntityChange entityChange) {
            if (!properties.enabled()) {
                ruleEngineService.onEntityChange(entityChange);
                return WorkflowDispatchResult.empty();
            }
            ruleEngineService.onEntityChange(entityChange);
            WorkflowDispatchResult canonical = dispatchCanonicalEntity(entityChange);
            ruleEngineService.onEntityChange(entityChange);
            return canonical;
        }
        if (dispatch instanceof WorkflowTriggerDispatch.ScheduleTick scheduleTick) {
            if (!properties.enabled()) {
                ruleEngineService.runSchedule(scheduleTick);
                return WorkflowDispatchResult.empty();
            }
            ruleEngineService.runSchedule(scheduleTick);
            WorkflowDispatchResult canonical = dispatchCanonicalSchedule(scheduleTick);
            ruleEngineService.runSchedule(scheduleTick);
            return canonical;
        }
        return WorkflowDispatchResult.empty();
    }

    public WorkflowResumeResult resume(WorkflowRunResumeCommand command) {
        return traversalService.resume(command);
    }

    private WorkflowDispatchResult dispatchCanonicalEntity(
            WorkflowTriggerDispatch.EntityChange dispatch) {
        List<Integer> candidates = workflowMapper.getEnabledCanonicalIdsByTrigger(
            dispatch.workspaceId(), "entity_change");
        Counters counters = new Counters(candidates.size());
        for (int workflowId : candidates) {
            try {
                resumeClaim(claimService.claimEntity(workflowId, dispatch), counters);
            } catch (RuntimeException failure) {
                counters.rejected++;
                log.warn(
                    "Canonical workflow entity dispatch failed workflowId={} exceptionClass={}",
                    workflowId,
                    failure.getClass().getSimpleName());
            }
        }
        return counters.result();
    }

    private WorkflowDispatchResult dispatchCanonicalSchedule(
            WorkflowTriggerDispatch.ScheduleTick dispatch) {
        List<Integer> candidates = workflowMapper.getEnabledCanonicalIdsByTrigger(
            dispatch.workspaceId(), "schedule");
        Counters counters = new Counters(candidates.size());
        for (int workflowId : candidates) {
            try {
                resumeRunningScheduleClaims(
                    dispatch.workspaceId(), workflowId, dispatch.bucketKey(), counters);
                ScheduleEnrollment enrollment = claimService.scheduleEnrollment(
                    workflowId, dispatch);
                if (enrollment == null) {
                    counters.rejected++;
                    continue;
                }
                principalService.resolve(dispatch.workspaceId(), enrollment.version());
                boolean triggeredSend = hasTriggeredSend(enrollment);
                int limit = triggeredSend
                    ? triggeredSendGate.recipientLimit() : MAX_SCHEDULE_ENROLLMENTS;
                List<Integer> recordIds = segmentService.evaluate(
                    dispatch.workspaceId(),
                    enrollment.conditionActorId(),
                    enrollment.version().getRecordType(),
                    enrollment.condition().config(),
                    limit + 1);
                if (!triggeredSend && recordIds.size() > limit) {
                    throw new WorkflowExecutionException(
                        "enrollment_limit",
                        "The schedule enrollment exceeds the bounded fan-out limit.",
                        true);
                }
                if (triggeredSend && recordIds.size() > limit) {
                    recordRecipientLimit(dispatch.workspaceId(), workflowId, limit);
                    recordIds = recordIds.subList(0, limit);
                    counters.rejected++;
                }
                for (int recordId : recordIds) {
                    resumeClaim(claimService.claimScheduleRecord(
                        workflowId,
                        enrollment.version().getId(),
                        dispatch,
                        recordId), counters);
                }
            } catch (RuntimeException failure) {
                counters.rejected++;
                log.warn(
                    "Canonical workflow schedule dispatch failed workflowId={} exceptionClass={}",
                    workflowId,
                    failure.getClass().getSimpleName());
            }
        }
        return counters.result();
    }

    private static boolean hasTriggeredSend(ScheduleEnrollment enrollment) {
        return enrollment.compiled().nodes().values().stream()
            .filter(WorkflowNode.Action.class::isInstance)
            .map(WorkflowNode.Action.class::cast)
            .anyMatch(action -> action.config().getType() != null
                && "send_message".equalsIgnoreCase(action.config().getType().trim()));
    }

    private void recordRecipientLimit(int workspaceId, int workflowId, int limit) {
        log.warn(
            "Canonical workflow schedule truncated workflowId={} diagnosticCode={}",
            workflowId,
            "triggered_send_recipient_limit");
        auditService.recordStrict(
            "workflow.triggered_send.recipient_limit",
            "workflow",
            workflowId,
            "Workflow " + workflowId,
            "Scheduled send-message recipients were capped",
            Map.of("limit", limit, "code", "triggered_send_recipient_limit"));
    }

    private void resumeRunningScheduleClaims(
            int workspaceId,
            int workflowId,
            String triggerKey,
            Counters counters) {
        int replayLimit = Math.max(
            MAX_SCHEDULE_ENROLLMENTS, triggeredSendGate.recipientLimit());
        List<WorkflowRun> running = workflowRunMapper.getRunningByTrigger(
            workspaceId, workflowId, triggerKey, replayLimit + 1);
        if (running.size() > replayLimit) {
            throw new WorkflowExecutionException(
                "enrollment_limit",
                "The schedule replay exceeds the bounded fan-out limit.",
                true);
        }
        for (WorkflowRun run : running) {
            counters.replayed++;
            traversalService.resume(new WorkflowRunResumeCommand(
                workspaceId, run.getId(), run.getCurrentNodeId()));
        }
    }

    private void resumeClaim(CanonicalClaim claim, Counters counters) {
        if (claim.rejected()) {
            counters.rejected++;
            return;
        }
        if (claim.replayed()) {
            counters.replayed++;
        }
        if (claim.started()) {
            counters.started++;
        }
        WorkflowRun run = claim.run();
        if (run != null && "running".equals(run.getStatus())) {
            traversalService.resume(new WorkflowRunResumeCommand(
                run.getWorkspaceId(), run.getId(), run.getCurrentNodeId()));
        }
    }

    private static final class Counters {
        private final int candidates;
        private int started;
        private int replayed;
        private int rejected;

        private Counters(int candidates) {
            this.candidates = candidates;
        }

        private WorkflowDispatchResult result() {
            return new WorkflowDispatchResult(candidates, started, replayed, rejected);
        }
    }
}
