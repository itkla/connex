package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

/** Enqueues generation-pinned date reconciliation targets from workflow and deal mutations. */
@Service
@RequiredArgsConstructor
public class WorkflowDateIntakeService {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final SegmentMapper segmentMapper;
    private final WorkflowRuntimeProperties properties;

    public void ensureWorkspaceGate(int workspaceId, WorkflowDefinition definition) {
        if (dateTrigger(definition) != null) {
            outboxMapper.ensureWorkspaceGate(workspaceId);
        }
    }

    public void enqueueFull(
            Workflow workflow,
            WorkflowVersion version,
            WorkflowDefinition definition) {
        RuleTrigger trigger = dateTrigger(definition);
        if (trigger == null || !workflow.isEnabled()
                || workflow.getArchivedAt() != null
                || workflow.getIntakePausedAt() != null) {
            return;
        }
        Integer upperId = segmentMapper.maximumEntityId(
            workflow.getWorkspaceId(), "deal");
        WorkflowTriggerOutbox outbox = outbox(
            workflow.getWorkspaceId(),
            workflow.getId(),
            version.getId(),
            workflow.getRuntimeGeneration(),
            null,
            "date-reconcile:" + workflow.getRuntimeGeneration() + ":full:"
                + UUID.randomUUID());
        outbox.setRecordScanUpperId(upperId == null ? 0 : upperId);
        outboxMapper.insert(outbox);
    }

    public void enqueueDeal(int workspaceId, int dealId) {
        List<WorkflowOutboxTarget> targets = outboxMapper.findDateTargets(
            workspaceId, properties.maxTriggerFanout() + 1);
        requireBounded(targets);
        String mutationKey = UUID.randomUUID().toString();
        for (WorkflowOutboxTarget target : targets) {
            WorkflowTriggerOutbox outbox = outbox(
                workspaceId,
                target.getWorkflowId(),
                target.getWorkflowVersionId(),
                target.getRuntimeGeneration(),
                dealId,
                "date-reconcile:" + target.getRuntimeGeneration() + ":"
                    + dealId + ":" + mutationKey);
            outboxMapper.insert(outbox);
        }
    }

    public void enqueueWorkspaceFull(int workspaceId) {
        List<WorkflowOutboxTarget> targets = outboxMapper.findDateTargets(
            workspaceId, properties.maxTriggerFanout() + 1);
        requireBounded(targets);
        Integer upperId = segmentMapper.maximumEntityId(workspaceId, "deal");
        String mutationKey = UUID.randomUUID().toString();
        for (WorkflowOutboxTarget target : targets) {
            WorkflowTriggerOutbox outbox = outbox(
                workspaceId,
                target.getWorkflowId(),
                target.getWorkflowVersionId(),
                target.getRuntimeGeneration(),
                null,
                "date-reconcile:" + target.getRuntimeGeneration() + ":import:"
                    + mutationKey);
            outbox.setRecordScanUpperId(upperId == null ? 0 : upperId);
            outboxMapper.insert(outbox);
        }
    }

    private void requireBounded(List<WorkflowOutboxTarget> targets) {
        if (targets.size() > properties.maxTriggerFanout()) {
            throw new WorkflowExecutionException(
                "trigger_fanout_limit",
                "The date reconciliation exceeds its bounded workflow fan-out.",
                false);
        }
    }

    private static WorkflowTriggerOutbox outbox(
            int workspaceId,
            int workflowId,
            long versionId,
            long generation,
            Integer recordId,
            String dedupeKey) {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(workspaceId);
        outbox.setWorkflowId(workflowId);
        outbox.setWorkflowVersionId(versionId);
        outbox.setWorkflowRuntimeGeneration(generation);
        outbox.setTriggerType("date_reconcile");
        outbox.setTriggerEvent("expectedCloseDate");
        outbox.setTriggerKey(dedupeKey);
        outbox.setRecordType("deal");
        outbox.setRecordId(recordId);
        outbox.setRecordScanAfterId(0);
        outbox.setRecordScanUpperId(0);
        outbox.setDedupeKey(dedupeKey);
        return outbox;
    }

    static RuleTrigger dateTrigger(WorkflowDefinition definition) {
        if (definition == null || definition.nodes() == null) {
            return null;
        }
        return definition.nodes().stream()
            .filter(node -> node.id().equals(definition.entryNodeId()))
            .filter(WorkflowNode.Trigger.class::isInstance)
            .map(WorkflowNode.Trigger.class::cast)
            .map(WorkflowNode.Trigger::config)
            .filter(trigger -> trigger != null && "date".equals(trigger.getType()))
            .findFirst()
            .orElse(null);
    }
}
