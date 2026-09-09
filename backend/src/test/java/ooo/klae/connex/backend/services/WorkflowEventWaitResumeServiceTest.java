package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.TaskCompletionEvent;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowEventWait;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowWaitConfig;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkflowEventWaitMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowEventWaitResumeServiceTest {

    private static final LocalDateTime TIMEOUT = LocalDateTime.of(2027, 3, 8, 19, 0);

    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private WorkflowEventWaitMapper eventWaitMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private WorkflowTraversalService traversalService;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private WorkflowRecordPolicyService recordPolicyService;
    @Mock private AutomationExecutor automationExecutor;
    @Mock private WorkspaceService workspaceService;
    @Mock private CompiledWorkflow compiled;

    private WorkflowEventWaitResumeService service;
    private WorkflowRun run;
    private WorkflowEventWait wait;

    @BeforeEach
    void setUp() {
        service = new WorkflowEventWaitResumeService(
            runMapper,
            versionMapper,
            eventWaitMapper,
            taskMapper,
            traversalService,
            principalService,
            recordPolicyService,
            automationExecutor,
            workspaceService);
        run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(29L);
        run.setStatus("running");
        run.setWaitKind("event");
        run.setCurrentNodeId("wait-task");
        run.setActorUserId(17);
        run.setAttributionUserId(17);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(29L);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        User actor = new User();
        actor.setId(17);
        WorkflowExecutionPrincipal principal = new WorkflowExecutionPrincipal(
            actor, "member", 17, 17);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of()), Map.of(17, Set.of()));
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId(53L);
        step.setNodeId("wait-task");
        step.setNodeType("wait");
        step.setStatus("waiting");
        wait = new WorkflowEventWait();
        wait.setId(61L);
        wait.setWorkflowStepRunId(53L);
        wait.setNodeId("wait-task");
        wait.setSourceTaskId(71);
        wait.setTimeoutAt(TIMEOUT);
        WorkflowNode.Wait node = new WorkflowNode.Wait(
            "wait-task",
            new WorkflowWaitConfig(
                "event",
                "task.completed",
                new WorkflowWaitConfig.Source("create-task", "taskId"),
                604800));
        lenient().when(runMapper.getByIdInWorkspace(7, 31L)).thenReturn(run);
        lenient().when(versionMapper.getById(7, 11, 29L)).thenReturn(version);
        lenient().when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        lenient().when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        lenient().when(automationExecutor.runAs(
                eq(7), any(User.class), eq("member"), any()))
            .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
        lenient().when(runMapper.getOwnedByIdForUpdate(7, 31L, "lease")).thenReturn(run);
        lenient().when(traversalService.compiled(run)).thenReturn(compiled);
        lenient().when(compiled.node("wait-task")).thenReturn(node);
        lenient().when(runMapper.getStepByNodeForUpdate(7, 31L, "wait-task"))
            .thenReturn(step);
        lenient().when(eventWaitMapper.getByRunNodeForUpdate(7, 31L, "wait-task"))
            .thenReturn(wait);
        lenient().when(runMapper.currentTimestamp(7, 11)).thenReturn(TIMEOUT);
    }

    @Test
    void committedCompletionAtTheDeadlineWinsOverTimeout() {
        TaskCompletionEvent completion = new TaskCompletionEvent();
        completion.setId(79L);
        completion.setOccurredAt(TIMEOUT);
        when(eventWaitMapper.getEarliestCompletion(7, 71, TIMEOUT)).thenReturn(completion);
        WorkflowEdge edge = new WorkflowEdge(
            "completed-edge", "wait-task", "activity", WorkflowEdge.Outcome.COMPLETED);
        when(compiled.transition("wait-task", WorkflowEdge.Outcome.COMPLETED))
            .thenReturn(edge);
        when(eventWaitMapper.resolve(7, 61L, "completed", 79L, TIMEOUT)).thenReturn(1);
        when(runMapper.succeedWaitingEventStep(
            7, 31L, "wait-task", "completed", "completed-edge", "activity", TIMEOUT))
            .thenReturn(1);
        when(runMapper.advanceClaimedRun(
            7, 31L, "wait-task", "activity", "lease")).thenReturn(1);

        assertTrue(service.resume(7, 31L, "lease"));

        verify(taskMapper).getTaskByIdForUpdate(7, 71);
        verify(eventWaitMapper).resolve(7, 61L, "completed", 79L, TIMEOUT);
    }

    @Test
    void deadlineWithoutCommittedCompletionChoosesTimeout() {
        WorkflowEdge edge = new WorkflowEdge(
            "timeout-edge", "wait-task", "stopped", WorkflowEdge.Outcome.TIMEOUT);
        when(compiled.transition("wait-task", WorkflowEdge.Outcome.TIMEOUT)).thenReturn(edge);
        when(eventWaitMapper.resolve(7, 61L, "timeout", null, TIMEOUT)).thenReturn(1);
        when(runMapper.succeedWaitingEventStep(
            7, 31L, "wait-task", "timeout", "timeout-edge", "stopped", TIMEOUT))
            .thenReturn(1);
        when(runMapper.advanceClaimedRun(
            7, 31L, "wait-task", "stopped", "lease")).thenReturn(1);

        assertTrue(service.resume(7, 31L, "lease"));

        verify(taskMapper).getTaskByIdForUpdate(7, 71);
        verify(eventWaitMapper).resolve(7, 61L, "timeout", null, TIMEOUT);
    }

    @Test
    void cancellationWinningTheResolutionCasPreventsASecondOutcome() {
        TaskCompletionEvent completion = new TaskCompletionEvent();
        completion.setId(79L);
        completion.setOccurredAt(TIMEOUT.minusSeconds(1));
        when(eventWaitMapper.getEarliestCompletion(7, 71, TIMEOUT)).thenReturn(completion);
        WorkflowEdge edge = new WorkflowEdge(
            "completed-edge", "wait-task", "activity", WorkflowEdge.Outcome.COMPLETED);
        when(compiled.transition("wait-task", WorkflowEdge.Outcome.COMPLETED))
            .thenReturn(edge);
        when(eventWaitMapper.resolve(7, 61L, "completed", 79L, TIMEOUT)).thenReturn(0);

        assertFalse(service.resume(7, 31L, "lease"));

        verify(runMapper, never()).succeedWaitingEventStep(
            7, 31L, "wait-task", "completed", "completed-edge", "activity", TIMEOUT);
        verify(runMapper, never()).advanceClaimedRun(
            7, 31L, "wait-task", "activity", "lease");
    }
}
