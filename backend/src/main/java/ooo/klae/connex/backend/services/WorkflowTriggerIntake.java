package ooo.klae.connex.backend.services;

/** Intake seam that WS2 can replace with a transactionally durable outbox. */
public interface WorkflowTriggerIntake {

    WorkflowDispatchResult enqueue(WorkflowTriggerDispatch dispatch);
}
