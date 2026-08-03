package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Immediate best-effort intake used until WS2 installs the durable trigger outbox. */
@Service
@RequiredArgsConstructor
public class ImmediateWorkflowTriggerIntake implements WorkflowTriggerIntake {

    private final WorkflowRuntimeService runtimeService;

    @Override
    public WorkflowDispatchResult enqueue(WorkflowTriggerDispatch dispatch) {
        return runtimeService.dispatch(dispatch);
    }
}
