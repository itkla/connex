package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Bounds aggregate trigger intake while the authoring transaction holds the admission mutex. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerAdmissionService {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowRuntimeProperties properties;

    /**
     * Requires READ COMMITTED and the principal service's admission mutex before any activation
     * write. A persisted trigger whose type, event list, or cadence is absent matches no intake
     * selector, so it consumes no capacity and is admitted rather than failing the caller.
     */
    void requireCapacity(
            int workspaceId, int workflowId, String recordType, RuleTrigger trigger) {
        if (trigger == null || trigger.getType() == null) {
            return;
        }
        int limit = properties.maxTriggerFanout() + 1;
        String triggerType = trigger.getType().trim().toLowerCase(Locale.ROOT);
        if ("entity_change".equals(triggerType)) {
            if (trigger.getEvents() == null) {
                return;
            }
            for (String event : trigger.getEvents()) {
                if (event == null) {
                    continue;
                }
                requireCapacity(workflowId, outboxMapper.findEntityTargets(
                    workspaceId, recordType, event, limit));
            }
        } else if ("schedule".equals(triggerType)) {
            if (trigger.getCadence() == null) {
                return;
            }
            requireCapacity(workflowId, outboxMapper.findScheduleTargets(
                workspaceId, trigger.getCadence().trim().toLowerCase(Locale.ROOT), limit));
        } else {
            throw new ConflictException("Workflow trigger is invalid");
        }
    }

    private void requireCapacity(int workflowId, List<WorkflowOutboxTarget> targets) {
        long others = targets.stream()
            .filter(target -> target.getWorkflowId() != workflowId)
            .count();
        if (others >= properties.maxTriggerFanout()) {
            throw new ConflictException("Workflow trigger capacity is exhausted");
        }
    }
}
