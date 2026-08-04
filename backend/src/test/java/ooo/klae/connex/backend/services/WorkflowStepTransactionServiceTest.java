package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

@ExtendWith(MockitoExtension.class)
class WorkflowStepTransactionServiceTest {

    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private WorkflowNodeExecutor nodeExecutor;

    private WorkflowStepTransactionService service;
    private WorkflowRun run;
    private WorkflowVersion version;
    private CompiledWorkflow compiled;

    @BeforeEach
    void setUp() {
        service = new WorkflowStepTransactionService(
            workflowRunMapper,
            workflowVersionMapper,
            principalService,
            recordGuard,
            nodeExecutor);
        run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        run.setActorUserId(17);
        run.setAttributionUserId(17);
        version = new WorkflowVersion();
        version.setId(19L);
        User actor = new User();
        actor.setId(17);
        WorkflowExecutionPrincipal principal = new WorkflowExecutionPrincipal(
            actor, "member", 17, 17);
        RuleAction action = new RuleAction();
        action.setType("notify");
        WorkflowNode.Action node = new WorkflowNode.Action("action", action);
        WorkflowEdge edge = new WorkflowEdge(
            "action-end", "action", "end", WorkflowEdge.Outcome.NEXT);
        compiled = new CompiledWorkflow(
            "trigger",
            Map.of("action", node),
            Map.of("action", NodeType.ACTION),
            Map.of("action", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("action"),
            null);
        lenient().when(workflowRunMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
        lenient().when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        lenient().when(principalService.resolve(7, version)).thenReturn(principal);
    }

    @Test
    void actionAndCheckpointAreWrittenInTheSameOrderedBoundary() {
        when(nodeExecutor.execute(any(), any())).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "action", "end")).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "action", compiled);

        assertTrue(result.executed());
        InOrder order = inOrder(
            principalService,
            recordGuard,
            nodeExecutor,
            workflowRunMapper);
        order.verify(principalService).resolve(7, version);
        order.verify(recordGuard).requireAccessible(run);
        order.verify(nodeExecutor).execute(any(), eq(compiled.node("action")));
        order.verify(workflowRunMapper).insertStep(any());
        order.verify(workflowRunMapper).advanceRun(7, 31L, "action", "end");
    }

    @Test
    void failureBeforeCheckpointWritesNoStepOrCursor() {
        when(nodeExecutor.execute(any(), any())).thenThrow(
            new IllegalStateException("crash before commit"));

        assertThrows(
            IllegalStateException.class,
            () -> service.execute(7, 31L, "action", compiled));

        verify(workflowRunMapper, never()).insertStep(any());
        verify(workflowRunMapper, never()).advanceRun(
            anyInt(), anyLong(), any(), any());
    }

    @Test
    void replayAfterCommittedCheckpointDoesNotExecuteTheActionAgain() {
        run.setCurrentNodeId("end");

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "action", compiled);

        assertFalse(result.executed());
        verify(nodeExecutor, never()).execute(any(), any());
        verify(workflowRunMapper, never()).insertStep(any());
    }

    @Test
    void eachNodeUsesAnIndependentReadCommittedTransaction() throws Exception {
        Method execute = WorkflowStepTransactionService.class.getMethod(
            "execute", int.class, long.class, String.class, CompiledWorkflow.class);
        Transactional transaction = execute.getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    @Test
    void delayAtomicallyPersistsOneWaitingStepAndDatabaseTimedRunWait() {
        run.setCurrentNodeId("delay");
        WorkflowNode.Delay delay = new WorkflowNode.Delay(
            "delay", new WorkflowDelayConfig(3_600));
        WorkflowEdge edge = new WorkflowEdge(
            "delay-end", "delay", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow delayWorkflow = new CompiledWorkflow(
            "trigger",
            Map.of("delay", delay),
            Map.of("delay", NodeType.DELAY),
            Map.of("delay", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("delay"),
            null);
        when(workflowRunMapper.getOwnedByIdForUpdate(7, 31L, "owner"))
            .thenReturn(run);
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.waitForDelay(
            7, 31L, "delay", "owner", 3_600)).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.executeClaimed(
            7, 31L, "delay", delayWorkflow, "owner");

        assertTrue(result.suspended());
        ArgumentCaptor<ooo.klae.connex.backend.beans.WorkflowStepRun> step =
            ArgumentCaptor.forClass(
                ooo.klae.connex.backend.beans.WorkflowStepRun.class);
        verify(workflowRunMapper).insertStep(step.capture());
        assertEquals("waiting", step.getValue().getStatus());
        assertEquals("none", step.getValue().getRetrySafety());
        verify(workflowRunMapper).waitForDelay(
            7, 31L, "delay", "owner", 3_600);
    }
}
