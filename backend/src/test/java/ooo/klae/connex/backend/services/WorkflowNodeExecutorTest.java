package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowNodeExecutorTest {

    @Mock private SegmentService segmentService;
    @Mock private RuleActionExecutor actionExecutor;
    @Mock private AutomationExecutor automationExecutor;
    @Mock private CompiledWorkflow compiled;

    private WorkflowNodeExecutor executor;
    private WorkflowRun run;
    private WorkflowNodeExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new WorkflowNodeExecutor(
            segmentService, actionExecutor, automationExecutor);
        run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setTriggerType("entity_change");
        run.setRecordType("deal");
        run.setRecordId(41);
        User actor = new User();
        actor.setId(17);
        context = new WorkflowNodeExecutionContext(
            run,
            new WorkflowVersion(),
            compiled,
            new WorkflowExecutionPrincipal(actor, "member", 17, 17));
    }

    @Test
    void entityConditionsTraverseBothOutcomes() {
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition(
            "condition", definition);
        when(segmentService.matchesEntity(7, 17, "deal", definition, 41))
            .thenReturn(true, false);

        assertEquals(WorkflowEdge.Outcome.YES,
            executor.execute(context, condition).outcome());
        assertEquals(WorkflowEdge.Outcome.NO,
            executor.execute(context, condition).outcome());
    }

    @Test
    void scheduleEnrollmentAlwaysRecordsYesWithoutTraversingNo() {
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition(
            "enrollment", definition);
        run.setTriggerType("schedule");
        when(compiled.enrollmentConditionNodeId()).thenReturn("enrollment");

        WorkflowStepTransition transition = executor.execute(context, condition);

        assertEquals(WorkflowEdge.Outcome.YES, transition.outcome());
        verify(segmentService, never()).matchesEntity(
            any(Integer.class), any(Integer.class), any(), any(), any(Integer.class));
    }

    @Test
    void triggerActionAndEndUseDeterministicContinuations() {
        WorkflowStepTransition trigger = executor.execute(
            context, new WorkflowNode.Trigger("trigger", null));
        assertEquals(WorkflowStepTransition.Continuation.IMMEDIATE, trigger.continuation());
        assertEquals(WorkflowEdge.Outcome.NEXT, trigger.outcome());

        RuleAction action = new RuleAction();
        action.setType("notify");
        when(automationExecutor.runAs(eq(7), any(User.class), eq("member"), any()))
            .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
        WorkflowStepTransition actionResult = executor.execute(
            context, new WorkflowNode.Action("action", action));
        assertEquals(WorkflowEdge.Outcome.NEXT, actionResult.outcome());
        verify(actionExecutor).execute(eq(action), any(WorkflowActionContext.class));

        WorkflowStepTransition end = executor.execute(
            context, new WorkflowNode.End("end"));
        assertEquals(WorkflowStepTransition.Continuation.TERMINAL, end.continuation());
        assertEquals(null, end.outcome());
    }
}
