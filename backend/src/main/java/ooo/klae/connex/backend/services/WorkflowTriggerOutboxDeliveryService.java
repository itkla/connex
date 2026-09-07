package ooo.klae.connex.backend.services;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowRuntimeClaimService.ScheduleEnrollment;

/** Converts one owned durable trigger target into legacy effects or queued canonical runs. */
@Service
@RequiredArgsConstructor
public class WorkflowTriggerOutboxDeliveryService {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final SegmentMapper segmentMapper;
    private final SegmentService segmentService;
    private final WorkflowRuntimeClaimService claimService;
    private final WorkflowExecutionPrincipalService principalService;
    private final RuleEngineService ruleEngineService;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowTriggeredSendGate triggeredSendGate;
    private final AuditService auditService;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public DeliveryResult deliver(int workspaceId, long outboxId, String leaseOwner) {
        WorkflowTriggerOutbox discoveredOutbox = outboxMapper.getById(workspaceId, outboxId);
        if (discoveredOutbox == null) {
            return DeliveryResult.STALE;
        }
        Workflow discovered = workflowMapper.getById(
            workspaceId, discoveredOutbox.getWorkflowId());
        WorkflowExecutionPrincipal principal = lockDispatchPrincipal(discoveredOutbox);
        outboxMapper.ensureWorkspaceGate(workspaceId);
        WorkflowTriggerOutbox outbox = outboxMapper.getOwnedForUpdate(
            workspaceId, outboxId, leaseOwner);
        if (outbox == null) {
            return DeliveryResult.STALE;
        }
        if (!sameTarget(discoveredOutbox, outbox) || !stateMatches(discovered, outbox)) {
            requireUpdated(outboxMapper.invalidate(workspaceId, outboxId, leaseOwner));
            return DeliveryResult.INVALIDATED;
        }
        Workflow workflow = workflowMapper.getByIdForUpdate(
            workspaceId, outbox.getWorkflowId());
        if (!stateMatches(workflow, outbox)) {
            requireUpdated(outboxMapper.invalidate(workspaceId, outboxId, leaseOwner));
            return DeliveryResult.INVALIDATED;
        }
        if ("entity_change".equals(outbox.getTriggerType())) {
            deliverEntity(outbox, principal);
            requireUpdated(outboxMapper.complete(workspaceId, outboxId, leaseOwner));
            return DeliveryResult.COMPLETED;
        }
        if ("schedule".equals(outbox.getTriggerType())) {
            deliverSchedule(outbox, leaseOwner, principal);
            return DeliveryResult.COMPLETED;
        }
        throw new WorkflowExecutionException(
            "trigger_type_invalid",
            "The durable workflow trigger type is invalid.",
            true);
    }

    private void deliverEntity(
            WorkflowTriggerOutbox outbox, WorkflowExecutionPrincipal principal) {
        Integer recordId = outbox.getRecordId();
        if (recordId == null || outbox.getOccurredAt() == null) {
            throw new WorkflowExecutionException(
                "trigger_payload_invalid",
                "The durable workflow trigger payload is invalid.",
                true);
        }
        WorkflowTriggerDispatch.EntityChange dispatch =
            new WorkflowTriggerDispatch.EntityChange(
                outbox.getWorkspaceId(),
                outbox.getRecordType(),
                recordId,
                outbox.getTriggerEvent(),
                outbox.getTriggerKey(),
                outbox.getOccurredAt().toInstant(ZoneOffset.UTC));
        ruleEngineService.onEntityChangeForWorkflow(
            outbox.getWorkflowId(), dispatch, principal);
        claimService.claimOutbox(outbox, recordId);
        ruleEngineService.onEntityChangeForWorkflow(
            outbox.getWorkflowId(), dispatch, principal);
    }

    private void deliverSchedule(
            WorkflowTriggerOutbox outbox,
            String leaseOwner,
            WorkflowExecutionPrincipal principal) {
        ScheduleEnrollment enrollment = claimService.outboxScheduleEnrollment(outbox);
        if (enrollment == null) {
            requireUpdated(outboxMapper.invalidate(
                outbox.getWorkspaceId(), outbox.getId(), leaseOwner));
            return;
        }
        List<Integer> recordIds = segmentMapper.entityIdsPage(
            outbox.getWorkspaceId(),
            outbox.getRecordType(),
            outbox.getRecordScanAfterId(),
            outbox.getRecordScanUpperId(),
            properties.maxScheduleRecordsPerPage());
        WorkflowTriggerDispatch.ScheduleTick dispatch =
            new WorkflowTriggerDispatch.ScheduleTick(
                outbox.getWorkspaceId(),
                outbox.getTriggerEvent(),
                outbox.getTriggerKey());
        boolean triggeredSend = enrollment.compiled().nodes().values().stream()
            .filter(WorkflowNode.Action.class::isInstance)
            .map(WorkflowNode.Action.class::cast)
            .anyMatch(action -> action.config().getType() != null
                && "send_message".equalsIgnoreCase(action.config().getType().trim()));
        int matchedCount = outbox.getScheduleMatchCount();
        for (int recordId : recordIds) {
            boolean matched = segmentService.matchesEntity(
                outbox.getWorkspaceId(),
                enrollment.conditionActorId(),
                outbox.getRecordType(),
                enrollment.condition().config(),
                recordId);
            if (!matched) {
                continue;
            }
            if (triggeredSend && matchedCount >= triggeredSendGate.recipientLimit()) {
                recordRecipientLimit(outbox);
                requireUpdated(outboxMapper.deadLetter(
                    outbox.getWorkspaceId(),
                    outbox.getId(),
                    leaseOwner,
                    "triggered_send_recipient_limit"));
                return;
            }
            ruleEngineService.runScheduleRecordForWorkflow(
                outbox.getWorkflowId(), dispatch, recordId, principal);
            claimService.claimOutbox(outbox, recordId);
            ruleEngineService.runScheduleRecordForWorkflow(
                outbox.getWorkflowId(), dispatch, recordId, principal);
            if (triggeredSend) {
                matchedCount++;
            }
        }
        int afterId = recordIds.isEmpty()
            ? outbox.getRecordScanUpperId()
            : recordIds.getLast();
        boolean completed = afterId >= outbox.getRecordScanUpperId();
        requireUpdated(outboxMapper.saveSchedulePage(
            outbox.getWorkspaceId(),
            outbox.getId(),
            leaseOwner,
            afterId,
            matchedCount,
            completed));
        if (completed) {
            outboxMapper.resolveDeadForWorkflow(
                outbox.getWorkspaceId(), outbox.getWorkflowId());
        }
    }

    private void recordRecipientLimit(WorkflowTriggerOutbox outbox) {
        auditService.recordStrict(
            "workflow.triggered_send.recipient_limit",
            "workflow",
            outbox.getWorkflowId(),
            "Workflow " + outbox.getWorkflowId(),
            "Scheduled send-message recipients were capped",
            Map.of(
                "limit", triggeredSendGate.recipientLimit(),
                "code", "triggered_send_recipient_limit"));
    }

    private WorkflowExecutionPrincipal lockDispatchPrincipal(
            WorkflowTriggerOutbox outbox) {
        WorkflowVersion version = versionMapper.getById(
            outbox.getWorkspaceId(), outbox.getWorkflowId(), outbox.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
                true);
        }
        return principalService.resolveLocked(outbox.getWorkspaceId(), version);
    }

    private static boolean sameTarget(
            WorkflowTriggerOutbox discovered, WorkflowTriggerOutbox locked) {
        return discovered.getWorkflowId() == locked.getWorkflowId()
            && discovered.getWorkflowVersionId() == locked.getWorkflowVersionId()
            && discovered.getWorkflowRuntimeGeneration()
                == locked.getWorkflowRuntimeGeneration();
    }

    private static boolean stateMatches(
            Workflow workflow, WorkflowTriggerOutbox outbox) {
        return workflow != null
            && workflow.isEnabled()
            && workflow.getArchivedAt() == null
            && workflow.getActiveVersionId() != null
            && workflow.getActiveVersionId() == outbox.getWorkflowVersionId()
            && workflow.getRuntimeGeneration() == outbox.getWorkflowRuntimeGeneration();
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Durable workflow trigger ownership was lost");
        }
    }

    /** Durable delivery outcome without record content. */
    public enum DeliveryResult {
        COMPLETED,
        INVALIDATED,
        STALE
    }
}
