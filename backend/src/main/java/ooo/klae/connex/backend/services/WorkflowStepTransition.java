package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.dto.WorkflowEdge;

/** Deterministic result of executing one workflow node. */
public record WorkflowStepTransition(
    Continuation continuation,
    WorkflowEdge.Outcome outcome,
    WorkflowActionResult actionResult
) {

    public WorkflowStepTransition {
        actionResult = actionResult == null ? WorkflowActionResult.none() : actionResult;
    }

    public WorkflowStepTransition(
            Continuation continuation, WorkflowEdge.Outcome outcome) {
        this(continuation, outcome, WorkflowActionResult.none());
    }

    /** Returns this transition with the action-specific durable outcome attached. */
    public WorkflowStepTransition withActionResult(WorkflowActionResult result) {
        return new WorkflowStepTransition(continuation, outcome, result);
    }

    /** Whether traversal continues now, suspends durably, or terminates. */
    public enum Continuation {
        IMMEDIATE,
        SUSPENDED,
        TERMINAL
    }
}
