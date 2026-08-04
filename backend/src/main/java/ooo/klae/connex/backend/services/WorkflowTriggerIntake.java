package ooo.klae.connex.backend.services;

/** Transactional intake seam for durable workflow trigger targets. */
public interface WorkflowTriggerIntake {

    WorkflowDispatchResult enqueue(WorkflowTriggerDispatch dispatch);
}
