package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowNode;

/** Executes one supported schema-v1 node without owning transaction boundaries. */
@Service
@RequiredArgsConstructor
public class WorkflowNodeExecutor {

    private final RuleActionExecutor actionExecutor;
    private final AutomationExecutor automationExecutor;
    private final WorkflowNodeDecisionService decisionService;
    private final WorkflowActionGuard actionGuard;

    public WorkflowStepTransition execute(
            WorkflowNodeExecutionContext context, WorkflowNode node) {
        WorkflowStepTransition transition = decisionService.decide(
            decisionContext(context), node);
        if (node instanceof WorkflowNode.Action action) {
            WorkflowDiagnosticDto blocker = actionGuard.blocker(
                context.run().getWorkspaceId(),
                context.principal().actorUserId(),
                context.run().getRecordType(),
                context.run().getRecordId(),
                node.id(),
                action.config());
            if (blocker != null) {
                throw new WorkflowExecutionException(
                    blocker.code().value(),
                    "The workflow action is no longer executable.",
                    true);
            }
            WorkflowActionContext actionContext = new WorkflowActionContext(
                context.run().getWorkspaceId(),
                context.run().getId(),
                node.id(),
                context.run().getRecordType(),
                context.run().getRecordId(),
                context.principal().attributionUserId(),
                context.principal().actorUserId(),
                context.principal().lockedPermissions());
            WorkflowActionResult actionResult = automationExecutor.runAs(
                context.run().getWorkspaceId(),
                context.principal().principal(),
                context.principal().role(),
                () -> actionExecutor.execute(action.config(), actionContext));
            return transition.withActionResult(actionResult);
        }
        return transition;
    }

    private static WorkflowNodeDecisionContext decisionContext(
            WorkflowNodeExecutionContext context) {
        return new WorkflowNodeDecisionContext(
            context.run().getWorkspaceId(),
            context.principal().attributionUserId(),
            context.run().getTriggerType(),
            context.run().getRecordType(),
            context.run().getRecordId(),
            true,
            context.compiled());
    }
}
