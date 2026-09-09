package ooo.klae.connex.backend.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.mappers.WorkflowDateEnrollmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;

/** Promotes one due planned date occurrence into the ordinary leased trigger queue. */
@Service
@RequiredArgsConstructor
public class WorkflowDatePromotionService {

    private final WorkflowDateEnrollmentMapper enrollmentMapper;
    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;

    public boolean promoteOne(int workspaceId) {
        Long id = enrollmentMapper.findDuePlannedIdForUpdate(workspaceId);
        if (id == null) {
            return false;
        }
        WorkflowDateEnrollment enrollment = enrollmentMapper.getByIdForUpdate(workspaceId, id);
        if (enrollment == null || !"planned".equals(enrollment.getState())) {
            return false;
        }
        WorkflowVersion version = versionMapper.getById(
            workspaceId, enrollment.getWorkflowId(), enrollment.getWorkflowVersionId());
        RuleTrigger trigger = trigger(version);
        LocalDateTime now = enrollmentMapper.currentTimestamp(
            workspaceId, enrollment.getWorkflowId());
        if (now == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable", "The workflow is unavailable.", true);
        }
        if (trigger == null) {
            enrollmentMapper.markMissed(workspaceId, id, now);
            return true;
        }
        LocalDate today = now.toInstant(ZoneOffset.UTC)
            .atZone(java.time.ZoneId.of(trigger.getTimezone()))
            .toLocalDate();
        if (enrollment.getScheduledLocalDate().isBefore(today.minusDays(1))) {
            enrollmentMapper.markMissed(workspaceId, id, now);
            return true;
        }
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(workspaceId);
        outbox.setWorkflowId(enrollment.getWorkflowId());
        outbox.setWorkflowVersionId(enrollment.getWorkflowVersionId());
        outbox.setWorkflowRuntimeGeneration(enrollment.getWorkflowRuntimeGeneration());
        outbox.setWorkflowDateEnrollmentId(enrollment.getId());
        outbox.setTriggerType("date");
        outbox.setTriggerEvent("expectedCloseDate");
        outbox.setTriggerKey(enrollment.getSourceDate().toString());
        outbox.setRecordType("deal");
        outbox.setRecordId(enrollment.getRecordId());
        outbox.setDedupeKey("date:" + enrollment.getId());
        outbox.setRecordScanAfterId(0);
        outbox.setRecordScanUpperId(0);
        outboxMapper.insert(outbox);
        if (enrollmentMapper.markQueued(workspaceId, id, now) != 1) {
            throw new IllegalStateException("Workflow date enrollment was not queued");
        }
        return true;
    }

    private RuleTrigger trigger(WorkflowVersion version) {
        if (version == null) {
            return null;
        }
        WorkflowDefinition definition = canonicalizer.parseDefinition(version.getDefinitionJson());
        return WorkflowDateIntakeService.dateTrigger(definition);
    }
}
