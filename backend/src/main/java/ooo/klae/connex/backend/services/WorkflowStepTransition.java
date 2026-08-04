package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.dto.WorkflowEdge;

/** Deterministic result of executing one workflow node. */
public record WorkflowStepTransition(
    Continuation continuation,
    WorkflowEdge.Outcome outcome
) {

    /** Whether traversal continues now, suspends durably, or terminates. */
    public enum Continuation {
        IMMEDIATE,
        SUSPENDED,
        TERMINAL
    }
}
