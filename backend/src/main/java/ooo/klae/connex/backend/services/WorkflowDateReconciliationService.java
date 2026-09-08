package ooo.klae.connex.backend.services;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowDateEnrollmentMapper;

/** Reconciles bounded date-trigger source snapshots without starting workflow runs. */
@Service
@RequiredArgsConstructor
public class WorkflowDateReconciliationService {

    private final WorkflowDateEnrollmentMapper enrollmentMapper;
    private final DealMapper dealMapper;
    private final SegmentMapper segmentMapper;
    private final WorkflowDateScheduleService scheduleService;
    private final WorkflowRuntimeProperties properties;

    public Page reconcile(
            WorkflowTriggerOutbox outbox,
            WorkflowVersion version,
            RuleTrigger trigger) {
        if (outbox.getRecordId() != null) {
            reconcileRecord(outbox, version, trigger, outbox.getRecordId());
            return new Page(outbox.getRecordScanUpperId(), true);
        }
        List<Integer> recordIds = segmentMapper.entityIdsPage(
            outbox.getWorkspaceId(),
            "deal",
            outbox.getRecordScanAfterId(),
            outbox.getRecordScanUpperId(),
            properties.maxScheduleRecordsPerPage());
        for (int recordId : recordIds) {
            reconcileRecord(outbox, version, trigger, recordId);
        }
        int afterId = recordIds.isEmpty()
            ? outbox.getRecordScanUpperId() : recordIds.getLast();
        return new Page(afterId, afterId >= outbox.getRecordScanUpperId());
    }

    private void reconcileRecord(
            WorkflowTriggerOutbox outbox,
            WorkflowVersion version,
            RuleTrigger trigger,
            int recordId) {
        int workspaceId = outbox.getWorkspaceId();
        Deal deal = dealMapper.getDealByIdForUpdate(workspaceId, recordId);
        List<WorkflowDateEnrollment> pending = enrollmentMapper.getPendingByRecordForUpdate(
            workspaceId, outbox.getWorkflowId(), recordId);
        LocalDateTime now = enrollmentMapper.currentTimestamp(
            workspaceId, outbox.getWorkflowId());
        if (now == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable", "The workflow is unavailable.", true);
        }
        LocalDate sourceDate = sourceDate(deal);
        if (sourceDate == null) {
            terminalizePending(workspaceId, pending, now);
            return;
        }
        WorkflowDateScheduleService.Schedule schedule = scheduleService.resolve(
            sourceDate, trigger);
        for (WorkflowDateEnrollment enrollment : pending) {
            if (!sourceDate.equals(enrollment.getSourceDate())) {
                terminalize(workspaceId, enrollment, now);
            }
        }
        WorkflowDateEnrollment existing = enrollmentMapper.getByPeriodForUpdate(
            workspaceId,
            outbox.getWorkflowId(),
            recordId,
            "expectedCloseDate",
            sourceDate);
        LocalDate today = now.toInstant(ZoneOffset.UTC)
            .atZone(schedule.zone())
            .toLocalDate();
        if (existing == null) {
            WorkflowDateEnrollment enrollment = new WorkflowDateEnrollment();
            enrollment.setWorkspaceId(workspaceId);
            enrollment.setWorkflowId(outbox.getWorkflowId());
            enrollment.setWorkflowVersionId(version.getId());
            enrollment.setWorkflowRuntimeGeneration(outbox.getWorkflowRuntimeGeneration());
            enrollment.setRecordType("deal");
            enrollment.setRecordId(recordId);
            enrollment.setDateField("expectedCloseDate");
            enrollment.setSourceDate(sourceDate);
            enrollment.setScheduledLocalDate(schedule.scheduledLocalDate());
            enrollment.setDueAt(schedule.dueAt());
            boolean missed = schedule.scheduledLocalDate().isBefore(today);
            enrollment.setState(missed ? "missed" : "planned");
            enrollment.setResolvedAt(missed ? now : null);
            enrollmentMapper.insert(enrollment);
            return;
        }
        if ("planned".equals(existing.getState())) {
            enrollmentMapper.refreshPlanned(
                workspaceId,
                existing.getId(),
                version.getId(),
                outbox.getWorkflowRuntimeGeneration(),
                schedule.scheduledLocalDate(),
                schedule.dueAt());
            if (schedule.scheduledLocalDate().isBefore(today.minusDays(1))) {
                enrollmentMapper.markMissed(workspaceId, existing.getId(), now);
            }
        } else if ("superseded".equals(existing.getState())
                && existing.getQueuedAt() == null
                && existing.getWorkflowRunId() == null
                && !schedule.scheduledLocalDate().isBefore(today)) {
            enrollmentMapper.revive(
                workspaceId,
                existing.getId(),
                version.getId(),
                outbox.getWorkflowRuntimeGeneration(),
                schedule.scheduledLocalDate(),
                schedule.dueAt());
        }
    }

    private void terminalizePending(
            int workspaceId,
            List<WorkflowDateEnrollment> pending,
            LocalDateTime now) {
        for (WorkflowDateEnrollment enrollment : pending) {
            terminalize(workspaceId, enrollment, now);
        }
    }

    private void terminalize(
            int workspaceId,
            WorkflowDateEnrollment enrollment,
            LocalDateTime now) {
        if ("planned".equals(enrollment.getState())) {
            enrollmentMapper.supersede(workspaceId, enrollment.getId(), now);
        } else if ("queued".equals(enrollment.getState())) {
            enrollmentMapper.markQueuedMissed(workspaceId, enrollment.getId(), now);
        }
    }

    private static LocalDate sourceDate(Deal deal) {
        if (deal == null || deal.getExpectedCloseDate() == null
                || deal.getExpectedCloseDate().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(deal.getExpectedCloseDate());
        } catch (DateTimeException exception) {
            return null;
        }
    }

    /** Updated full-scan cursor and whether the bounded reconciliation target is complete. */
    public record Page(int afterId, boolean completed) { }
}
