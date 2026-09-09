package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.TaskCompletionEvent;
import ooo.klae.connex.backend.beans.WorkflowEventWait;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkflowEventWaitMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.tenant.Permission;

/** Commits one leased node effect and its durable checkpoint atomically. */
@Service
@RequiredArgsConstructor
public class WorkflowStepTransactionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowExecutionPrincipalService principalService;
    private final WorkflowRecordGuard recordGuard;
    private final WorkflowRecordPolicyService recordPolicyService;
    private final AutomationExecutor automationExecutor;
    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowActionBindingService bindingService;
    private final WorkspaceService workspaceService;
    private final TaskMapper taskMapper;
    private final WorkflowEventWaitMapper eventWaitMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public StepResult execute(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled) {
        return executeInternal(workspaceId, runId, expectedNodeId, compiled, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public StepResult executeClaimed(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled,
            String leaseOwner) {
        return executeInternal(
            workspaceId, runId, expectedNodeId, compiled, leaseOwner);
    }

    private StepResult executeInternal(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled,
            String leaseOwner) {
        WorkflowRun discoveredRun = workflowRunMapper.getByIdInWorkspace(workspaceId, runId);
        if (discoveredRun == null
                || !"running".equals(discoveredRun.getStatus())
                || discoveredRun.getCancelRequestedAt() != null
                || !Objects.equals(expectedNodeId, discoveredRun.getCurrentNodeId())) {
            return StepResult.noOp();
        }
        WorkflowVersion version = workflowVersionMapper.getById(
            workspaceId,
            discoveredRun.getWorkflowId(),
            discoveredRun.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
                true);
        }
        WorkflowNode node = compiled.node(expectedNodeId);
        NodeType nodeType = compiled.nodeType(expectedNodeId);
        if (node == null || nodeType == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow definition is inconsistent.",
                true);
        }
        WorkflowActionBindingService.Discovery discovery =
            node instanceof WorkflowNode.Action action && compiled.schemaVersion() >= 2
                ? discoverActionTargetAsPrincipal(
                    workspaceId, version, discoveredRun, compiled, action)
                : new WorkflowActionBindingService.Discovery(null);
        WorkspaceService.LockedPermissionSnapshot authorization = compiled.schemaVersion() >= 2
            ? lockAuthorization(workspaceId, version, discovery)
            : null;
        WorkflowExecutionPrincipal principal = authorization == null
            ? principalService.resolveLocked(workspaceId, version)
            : principalService.resolveLocked(workspaceId, version, authorization);
        WorkflowRun run = leaseOwner == null
            ? workflowRunMapper.getByIdForUpdate(workspaceId, runId)
            : workflowRunMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null
                || !"running".equals(run.getStatus())
                || run.getCancelRequestedAt() != null
                || !Objects.equals(expectedNodeId, run.getCurrentNodeId())
                || run.getWorkflowId() != discoveredRun.getWorkflowId()
                || run.getWorkflowVersionId() != discoveredRun.getWorkflowVersionId()) {
            return StepResult.noOp();
        }
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        if (!Objects.equals(run.getActorUserId(), principal.actorUserId())
                || !Objects.equals(run.getAttributionUserId(), principal.attributionUserId())) {
            throw new WorkflowExecutionException(
                "actor_changed",
                "The configured workflow actor changed after the run started.",
                true);
        }
        if (compiled.schemaVersion() >= 2) {
            String stopReason = runAs(run, principal,
                () -> recordPolicyService.stopReason(
                    run, compiled, principal.attributionUserId()));
            if (stopReason != null) {
                return stopBeforeNode(
                    run, nodeType, leaseOwner, startedAt, stopReason);
            }
        } else {
            runAs(run, principal, () -> {
                recordGuard.requireAccessible(run);
                return null;
            });
        }
        WorkflowNode executionNode = node;
        if (node instanceof WorkflowNode.Action action && compiled.schemaVersion() >= 2) {
            executionNode = new WorkflowNode.Action(
                action.id(),
                runAs(run, principal, () -> bindingService.resolveLocked(
                    run, action.config(), discovery, authorization, compiled)));
        }
        if (node instanceof WorkflowNode.Delay delay) {
            return enterDelay(run, delay, compiled, leaseOwner, startedAt);
        }
        if (node instanceof WorkflowNode.Wait wait) {
            return enterWait(run, wait, compiled, principal, leaseOwner, startedAt);
        }
        WorkflowStepRun reservedActionStep = node instanceof WorkflowNode.Action
                && leaseOwner != null
            ? requireReservedActionStep(run)
            : null;
        WorkflowNode finalExecutionNode = executionNode;
        WorkflowStepTransition transition = runAs(run, principal,
            () -> nodeExecutor.execute(
                new WorkflowNodeExecutionContext(
                    run, version, compiled, principal, authorization),
                finalExecutionNode));
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        WorkflowEdge edge = transition.outcome() == null
            ? null
            : compiled.transition(expectedNodeId, transition.outcome());
        if (transition.continuation() == WorkflowStepTransition.Continuation.IMMEDIATE
                && edge == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow transition is unavailable.",
                true);
        }
        if (reservedActionStep == null) {
            WorkflowStepRun step = successfulStep(
                run, nodeType, nextSequence(run), edge, transition, startedAt, finishedAt);
            workflowRunMapper.insertStep(step);
        } else {
            completeReservedAction(run, reservedActionStep, edge, transition, finishedAt);
        }
        if (transition.continuation() == WorkflowStepTransition.Continuation.TERMINAL) {
            WorkflowNode.End end = node instanceof WorkflowNode.End terminalEnd
                ? terminalEnd : null;
            boolean stopped = end != null
                && end.config() != null
                && "stopped".equals(end.config().outcome());
            String stopReason = stopped ? end.config().reason() : null;
            int terminal = stopped
                ? leaseOwner == null
                    ? workflowRunMapper.stopRun(
                        workspaceId, runId, expectedNodeId, stopReason, finishedAt)
                    : workflowRunMapper.stopClaimedRun(
                        workspaceId, runId, expectedNodeId, leaseOwner,
                        stopReason, finishedAt)
                : leaseOwner == null
                    ? workflowRunMapper.completeRun(
                        workspaceId, runId, expectedNodeId, finishedAt)
                    : workflowRunMapper.completeClaimedRun(
                        workspaceId, runId, expectedNodeId, leaseOwner, finishedAt);
            requireCheckpoint(terminal, "completion");
            return new StepResult(true, null, true, false);
        }
        if (transition.continuation() == WorkflowStepTransition.Continuation.SUSPENDED) {
            return new StepResult(true, expectedNodeId, false, true);
        }
        int advanced = leaseOwner == null
            ? workflowRunMapper.advanceRun(
                workspaceId, runId, expectedNodeId, edge.targetNodeId())
            : workflowRunMapper.advanceClaimedRun(
                workspaceId, runId, expectedNodeId, edge.targetNodeId(), leaseOwner);
        requireCheckpoint(advanced, "node");
        return new StepResult(true, edge.targetNodeId(), false, false);
    }

    private <T> T runAs(
            WorkflowRun run,
            WorkflowExecutionPrincipal principal,
            Supplier<T> work) {
        return automationExecutor.runAs(
            run.getWorkspaceId(), principal.principal(), principal.role(), work);
    }

    private WorkflowActionBindingService.Discovery discoverActionTarget(
            WorkflowRun run,
            CompiledWorkflow compiled,
            WorkflowNode.Action action) {
        if (compiled.schemaVersion() >= 2) {
            return bindingService.discover(run, action.config(), compiled);
        }
        String type = action.config() == null || action.config().getType() == null
            ? "" : action.config().getType().trim().toLowerCase(Locale.ROOT);
        Integer target = switch (type) {
            case "create_task", "notify" -> run.getAttributionUserId();
            case "assign_owner" -> action.config().getTargetUserId();
            default -> null;
        };
        return new WorkflowActionBindingService.Discovery(target);
    }

    private WorkflowActionBindingService.Discovery discoverActionTargetAsPrincipal(
            int workspaceId,
            WorkflowVersion version,
            WorkflowRun run,
            CompiledWorkflow compiled,
            WorkflowNode.Action action) {
        WorkflowExecutionPrincipal principal = principalService.resolve(workspaceId, version);
        return runAs(run, principal, () -> discoverActionTarget(run, compiled, action));
    }

    private static Map<Integer, Set<Permission>> requiredMembers(
            WorkflowVersion version,
            WorkflowActionBindingService.Discovery discovery) {
        int actorMemberId = actorMemberId(version);
        Map<Integer, Set<Permission>> required = new LinkedHashMap<>();
        required.put(actorMemberId, Set.of());
        if (discovery.targetUserId() != null) {
            required.putIfAbsent(discovery.targetUserId(), Set.of());
        }
        return Map.copyOf(required);
    }

    private WorkspaceService.LockedPermissionSnapshot lockAuthorization(
            int workspaceId,
            WorkflowVersion version,
            WorkflowActionBindingService.Discovery discovery) {
        try {
            return workspaceService.lockAndRequirePermissionsSnapshot(
                workspaceId, requiredMembers(version, discovery));
        } catch (ForbiddenException exception) {
            int actorMemberId = actorMemberId(version);
            String code = workspaceService.getRole(workspaceId, actorMemberId) == null
                ? "actor_unavailable" : "reference_unavailable";
            throw new WorkflowExecutionException(
                code,
                "A workflow member reference is no longer available.",
                true);
        }
    }

    private static int actorMemberId(WorkflowVersion version) {
        Integer actorMemberId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById() : version.getRunAsUserId();
        if (actorMemberId == null || actorMemberId < 1) {
            throw new WorkflowExecutionException(
                "actor_unavailable",
                "The configured workflow actor is unavailable.",
                true);
        }
        return actorMemberId;
    }

    private StepResult enterDelay(
            WorkflowRun run,
            WorkflowNode.Delay delay,
            CompiledWorkflow compiled,
            String leaseOwner,
            LocalDateTime startedAt) {
        if (leaseOwner == null || delay.config() == null) {
            throw new WorkflowExecutionException(
                "delay_runtime_unavailable",
                "The durable Delay runtime is unavailable.",
                true);
        }
        WorkflowEdge edge = compiled.transition(run.getCurrentNodeId(), WorkflowEdge.Outcome.NEXT);
        if (edge == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned Delay transition is unavailable.",
                true);
        }
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(run.getWorkspaceId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(nextSequence(run));
        step.setNodeId(run.getCurrentNodeId());
        step.setNodeType(NodeType.DELAY.name().toLowerCase(Locale.ROOT));
        step.setStatus("waiting");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setStartedAt(startedAt);
        workflowRunMapper.insertStep(step);
        if (workflowRunMapper.waitForDelay(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                leaseOwner,
                delay.config().durationSeconds()) != 1) {
            throw new IllegalStateException("Workflow Delay checkpoint was not suspended");
        }
        return new StepResult(true, run.getCurrentNodeId(), false, true);
    }

    private StepResult enterWait(
            WorkflowRun run,
            WorkflowNode.Wait wait,
            CompiledWorkflow compiled,
            WorkflowExecutionPrincipal principal,
            String leaseOwner,
            LocalDateTime startedAt) {
        if (leaseOwner == null || wait.config() == null || wait.config().source() == null) {
            throw new WorkflowExecutionException(
                "wait_runtime_unavailable",
                "The durable workflow wait runtime is unavailable.",
                true);
        }
        WorkflowStepRun sourceStep = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), wait.config().source().nodeId());
        int sourceTaskId = sourceTaskId(sourceStep, wait.config().source().output());
        LocalDateTime databaseNow = workflowRunMapper.currentTimestamp(
            run.getWorkspaceId(), run.getWorkflowId());
        if (databaseNow == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The workflow is unavailable.",
                true);
        }
        LocalDateTime timeoutAt = databaseNow.plusSeconds(wait.config().timeoutSeconds());
        Task task = taskMapper.getTaskByIdForUpdate(run.getWorkspaceId(), sourceTaskId);
        TaskCompletionEvent evidence = eventWaitMapper.getEarliestCompletion(
            run.getWorkspaceId(), sourceTaskId, timeoutAt);
        if (task == null && evidence == null) {
            throw new WorkflowExecutionException(
                "reference_unavailable",
                "The task produced by the workflow is no longer available.",
                true);
        }
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(run.getWorkspaceId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(nextSequence(run));
        step.setNodeId(run.getCurrentNodeId());
        step.setNodeType(NodeType.WAIT.name().toLowerCase(Locale.ROOT));
        step.setStatus("waiting");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setStartedAt(startedAt);
        workflowRunMapper.insertStep(step);
        WorkflowEventWait eventWait = new WorkflowEventWait();
        eventWait.setWorkspaceId(run.getWorkspaceId());
        eventWait.setWorkflowRunId(run.getId());
        eventWait.setWorkflowStepRunId(step.getId());
        eventWait.setSourceStepRunId(sourceStep.getId());
        eventWait.setNodeId(run.getCurrentNodeId());
        eventWait.setSourceTaskId(sourceTaskId);
        eventWait.setEventType("task.completed");
        eventWait.setTimeoutAt(timeoutAt);
        eventWaitMapper.insertWait(eventWait);
        if (workflowRunMapper.waitForEvent(
                run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(),
                leaseOwner, timeoutAt) != 1) {
            throw new IllegalStateException("Workflow event wait was not suspended");
        }
        return new StepResult(true, run.getCurrentNodeId(), false, true);
    }

    private static int sourceTaskId(WorkflowStepRun sourceStep, String output) {
        if (sourceStep == null
                || !"succeeded".equals(sourceStep.getStatus())
                || !"taskId".equals(output)
                || sourceStep.getActionOutputsJson() == null) {
            throw new WorkflowExecutionException(
                "reference_unavailable",
                "The workflow task output is unavailable.",
                true);
        }
        try {
            tools.jackson.databind.JsonNode outputs = JSON.readTree(
                sourceStep.getActionOutputsJson());
            tools.jackson.databind.JsonNode taskId = outputs == null
                ? null : outputs.get("taskId");
            if (taskId == null || !taskId.isIntegralNumber()
                    || !taskId.canConvertToInt() || taskId.intValue() < 1) {
                throw new IllegalArgumentException("taskId");
            }
            return taskId.intValue();
        } catch (Exception exception) {
            throw new WorkflowExecutionException(
                "reference_unavailable",
                "The workflow task output is unavailable.",
                true);
        }
    }

    private WorkflowStepRun requireReservedActionStep(WorkflowRun run) {
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step == null || !"running".equals(step.getStatus())) {
            throw new WorkflowExecutionException(
                "attempt_unavailable",
                "The workflow action attempt is unavailable.",
                true);
        }
        return step;
    }

    private StepResult stopBeforeNode(
            WorkflowRun run,
            NodeType nodeType,
            String leaseOwner,
            LocalDateTime finishedAt,
            String stopReason) {
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step == null) {
            step = new WorkflowStepRun();
            step.setWorkspaceId(run.getWorkspaceId());
            step.setWorkflowRunId(run.getId());
            step.setSequenceNumber(nextSequence(run));
            step.setNodeId(run.getCurrentNodeId());
            step.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
            step.setStatus("skipped");
            step.setAttemptCount(1);
            step.setRetrySafety("none");
            step.setStartedAt(finishedAt);
            step.setFinishedAt(finishedAt);
            workflowRunMapper.insertStep(step);
        } else {
            workflowRunMapper.abandonRunningAttempts(
                run.getWorkspaceId(), run.getId(), step.getId(),
                "stopped", finishedAt);
            if (workflowRunMapper.skipExistingStep(
                    run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(),
                    finishedAt) != 1) {
                throw new IllegalStateException("Workflow step was not stopped");
            }
        }
        int stopped = leaseOwner == null
            ? workflowRunMapper.stopRun(
                run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(),
                stopReason, finishedAt)
            : workflowRunMapper.stopClaimedRun(
                run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(),
                leaseOwner, stopReason, finishedAt);
        requireCheckpoint(stopped, "stop");
        return new StepResult(true, null, true, false);
    }

    private void completeReservedAction(
            WorkflowRun run,
            WorkflowStepRun step,
            WorkflowEdge edge,
            WorkflowStepTransition transition,
            LocalDateTime finishedAt) {
        if (workflowRunMapper.completeAttempt(
                run.getWorkspaceId(),
                run.getId(),
                step.getId(),
                step.getAttemptCount(),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action attempt was not completed");
        }
        if (workflowRunMapper.succeedExistingStep(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                step.getAttemptCount(),
                transition.outcome() == null ? null : transition.outcome().value(),
                edge == null ? null : edge.id(),
                edge == null ? null : edge.targetNodeId(),
                transition.actionResult().outcome(),
                transition.actionResult().referenceId(),
                outputsJson(transition.actionResult()),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action step was not completed");
        }
    }

    private int nextSequence(WorkflowRun run) {
        int sequence = workflowRunMapper.nextSequence(run.getWorkspaceId(), run.getId());
        if (sequence < 0 || sequence > 49) {
            throw new WorkflowExecutionException(
                "traversal_limit",
                "The workflow traversal exceeded its bounded node limit.",
                true);
        }
        return sequence;
    }

    private static WorkflowStepRun successfulStep(
            WorkflowRun run,
            NodeType nodeType,
            int sequence,
            WorkflowEdge edge,
            WorkflowStepTransition transition,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(run.getWorkspaceId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(sequence);
        step.setNodeId(run.getCurrentNodeId());
        step.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
        step.setStatus("succeeded");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setSelectedOutcome(transition.outcome() == null
            ? null : transition.outcome().value());
        step.setSelectedEdgeId(edge == null ? null : edge.id());
        step.setNextNodeId(edge == null ? null : edge.targetNodeId());
        step.setActionOutcome(transition.actionResult().outcome());
        step.setActionReferenceId(transition.actionResult().referenceId());
        step.setActionOutputsJson(outputsJson(transition.actionResult()));
        step.setStartedAt(startedAt);
        step.setFinishedAt(finishedAt);
        return step;
    }

    private static String outputsJson(WorkflowActionResult result) {
        if (result.outputs().isEmpty()) {
            return null;
        }
        try {
            String json = JSON.writeValueAsString(result.outputs());
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
                throw new WorkflowExecutionException(
                    "action_output_invalid",
                    "The workflow action output exceeds its durable limit.",
                    false);
            }
            return json;
        } catch (WorkflowExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkflowExecutionException(
                "action_output_invalid",
                "The workflow action output could not be persisted.",
                false);
        }
    }

    private static void requireCheckpoint(int updated, String checkpoint) {
        if (updated != 1) {
            throw new IllegalStateException(
                "Workflow run " + checkpoint + " checkpoint was not advanced");
        }
    }

    /** Result of one isolated node transaction. */
    public record StepResult(
        boolean executed,
        String nextNodeId,
        boolean terminal,
        boolean suspended
    ) {

        static StepResult noOp() {
            return new StepResult(false, null, false, false);
        }
    }
}
