package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Routes typed trigger envelopes into the correct transactional durable intake boundary. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerIntakeService implements WorkflowTriggerIntake {

    private final WorkflowTriggerIntakeTransaction transaction;

    @Override
    public WorkflowDispatchResult enqueue(WorkflowTriggerDispatch dispatch) {
        if (dispatch instanceof WorkflowTriggerDispatch.EntityChange entityChange) {
            return transaction.enqueueEntityChange(entityChange);
        }
        if (dispatch instanceof WorkflowTriggerDispatch.ScheduleTick scheduleTick) {
            return transaction.enqueueSchedule(scheduleTick);
        }
        return WorkflowDispatchResult.empty();
    }
}
