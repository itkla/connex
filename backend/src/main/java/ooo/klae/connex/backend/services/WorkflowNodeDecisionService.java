package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;

/** Selects deterministic workflow transitions without persistence or action side effects. */
@Service
@RequiredArgsConstructor
public class WorkflowNodeDecisionService {

    private final SegmentService segmentService;

    public WorkflowStepTransition decide(
            WorkflowNodeDecisionContext context, WorkflowNode node) {
        if (node instanceof WorkflowNode.Trigger) {
            return immediate(WorkflowEdge.Outcome.NEXT);
        }
        if (node instanceof WorkflowNode.Condition condition) {
            boolean enrollment = "schedule".equals(context.triggerType())
                && node.id().equals(context.compiled().enrollmentConditionNodeId());
            boolean matched = enrollment && context.scheduleEnrollmentConfirmed()
                || segmentService.matchesEntity(
                    context.workspaceId(),
                    context.attributionUserId(),
                    context.recordType(),
                    condition.config(),
                    context.recordId());
            return immediate(matched ? WorkflowEdge.Outcome.YES : WorkflowEdge.Outcome.NO);
        }
        if (node instanceof WorkflowNode.Action) {
            return immediate(WorkflowEdge.Outcome.NEXT);
        }
        if (node instanceof WorkflowNode.Delay) {
            return new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.SUSPENDED,
                null);
        }
        if (node instanceof WorkflowNode.End) {
            return new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.TERMINAL,
                null);
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
