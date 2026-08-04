package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Persists bounded per-workflow trigger targets before the source mutation commits. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerIntakeTransaction {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final SegmentMapper segmentMapper;
    private final WorkflowRuntimeProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowDispatchResult enqueueEntityChange(
            WorkflowTriggerDispatch.EntityChange dispatch) {
        List<WorkflowOutboxTarget> targets = outboxMapper.findEntityTargets(
            dispatch.workspaceId(),
            dispatch.recordType(),
            dispatch.event(),
            properties.maxTriggerFanout() + 1);
        requireBoundedFanout(targets);
        ensureWorkspaceGate(dispatch.workspaceId(), targets);
        for (WorkflowOutboxTarget target : targets) {
            WorkflowTriggerOutbox outbox = baseOutbox(
                dispatch.workspaceId(),
                target,
                "entity_change",
                dispatch.event(),
                dispatch.triggerKey(),
                "entity:" + dispatch.triggerKey());
            outbox.setRecordId(dispatch.recordId());
            outbox.setOccurredAt(LocalDateTime.ofInstant(
                dispatch.occurredAt(), ZoneOffset.UTC));
            outboxMapper.insert(outbox);
        }
        return new WorkflowDispatchResult(targets.size(), 0, 0, 0);
    }

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public WorkflowDispatchResult enqueueSchedule(
            WorkflowTriggerDispatch.ScheduleTick dispatch) {
        String cadence = normalize(dispatch.cadence());
        List<WorkflowOutboxTarget> targets = outboxMapper.findScheduleTargets(
            dispatch.workspaceId(), cadence, properties.maxTriggerFanout() + 1);
        requireBoundedFanout(targets);
        ensureWorkspaceGate(dispatch.workspaceId(), targets);
        for (WorkflowOutboxTarget target : targets) {
            Integer upperId = segmentMapper.maximumEntityId(
                dispatch.workspaceId(), target.getRecordType());
            WorkflowTriggerOutbox outbox = baseOutbox(
                dispatch.workspaceId(),
                target,
                "schedule",
                cadence,
                dispatch.bucketKey(),
                "schedule:" + cadence + ":" + dispatch.bucketKey());
            outbox.setRecordScanUpperId(upperId == null ? 0 : upperId);
            outboxMapper.insert(outbox);
        }
        return new WorkflowDispatchResult(targets.size(), 0, 0, 0);
    }

    private WorkflowTriggerOutbox baseOutbox(
            int workspaceId,
            WorkflowOutboxTarget target,
            String triggerType,
            String triggerEvent,
            String triggerKey,
            String dedupeKey) {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(workspaceId);
        outbox.setWorkflowId(target.getWorkflowId());
        outbox.setWorkflowVersionId(target.getWorkflowVersionId());
        outbox.setWorkflowRuntimeGeneration(target.getRuntimeGeneration());
        outbox.setTriggerType(triggerType);
        outbox.setTriggerEvent(triggerEvent);
        outbox.setTriggerKey(triggerKey);
        outbox.setRecordType(target.getRecordType());
        outbox.setDedupeKey(dedupeKey);
        outbox.setRecordScanAfterId(0);
        outbox.setRecordScanUpperId(0);
        return outbox;
    }

    private void requireBoundedFanout(List<WorkflowOutboxTarget> targets) {
        if (targets.size() > properties.maxTriggerFanout()) {
            throw new WorkflowExecutionException(
                "trigger_fanout_limit",
                "The trigger matches more workflows than the durable intake limit allows.",
                false);
        }
    }

    private void ensureWorkspaceGate(
            int workspaceId, List<WorkflowOutboxTarget> targets) {
        if (!targets.isEmpty()) {
            outboxMapper.ensureWorkspaceGate(workspaceId);
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
