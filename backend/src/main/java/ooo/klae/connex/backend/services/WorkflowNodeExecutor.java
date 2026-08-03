package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;

/** Executes one supported schema-v1 node without owning transaction boundaries. */
@Service
@RequiredArgsConstructor
public class WorkflowNodeExecutor {

    private final SegmentService segmentService;
    private final RuleActionExecutor actionExecutor;
    private final AutomationExecutor automationExecutor;

    public WorkflowStepTransition execute(
            WorkflowNodeExecutionContext context, WorkflowNode node) {
        if (node instanceof WorkflowNode.Trigger) {
            return immediate(WorkflowEdge.Outcome.NEXT);
        }
        if (node instanceof WorkflowNode.Condition condition) {
            boolean enrollment = "schedule".equals(context.run().getTriggerType())
                && node.id().equals(context.compiled().enrollmentConditionNodeId());
            boolean matched = enrollment || segmentService.matchesEntity(
                context.run().getWorkspaceId(),
                context.principal().attributionUserId(),
                context.run().getRecordType(),
                condition.config(),
                context.run().getRecordId());
            return immediate(matched ? WorkflowEdge.Outcome.YES : WorkflowEdge.Outcome.NO);
        }
        if (node instanceof WorkflowNode.Action action) {
            WorkflowActionContext actionContext = new WorkflowActionContext(
                context.run().getWorkspaceId(),
                context.run().getId(),
                node.id(),
                context.run().getRecordType(),
                context.run().getRecordId(),
                context.principal().attributionUserId());
            automationExecutor.runAs(
                context.run().getWorkspaceId(),
                context.principal().principal(),
                context.principal().role(),
                () -> {
                    actionExecutor.execute(action.config(), actionContext);
                    return null;
                });
            return immediate(WorkflowEdge.Outcome.NEXT);
        }
        if (node instanceof WorkflowNode.End) {
            return new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.TERMINAL, null);
        }
        throw new WorkflowExecutionException(
            "node_unsupported",
            "The active workflow contains an unsupported node.",
            true);
    }

    private static WorkflowStepTransition immediate(WorkflowEdge.Outcome outcome) {
        return new WorkflowStepTransition(
            WorkflowStepTransition.Continuation.IMMEDIATE, outcome);
    }
}
