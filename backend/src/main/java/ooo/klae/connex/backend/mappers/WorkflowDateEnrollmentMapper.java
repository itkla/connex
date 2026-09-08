package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.dto.WorkflowDateScheduleStatusDto;

/** Workspace-scoped date reconciliation, due promotion, and source-period persistence. */
public interface WorkflowDateEnrollmentMapper {

    void insert(WorkflowDateEnrollment enrollment);

    LocalDateTime currentTimestamp(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    WorkflowDateScheduleStatusDto getScheduleStatus(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    WorkflowDateEnrollment getByPeriodForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("recordId") int recordId,
        @Param("dateField") String dateField,
        @Param("sourceDate") java.time.LocalDate sourceDate);

    WorkflowDateEnrollment getByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<WorkflowDateEnrollment> getPendingByRecordForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("recordId") int recordId);

    int supersede(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("resolvedAt") LocalDateTime resolvedAt);

    int revive(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("workflowVersionId") long workflowVersionId,
        @Param("workflowRuntimeGeneration") long workflowRuntimeGeneration,
        @Param("scheduledLocalDate") java.time.LocalDate scheduledLocalDate,
        @Param("dueAt") LocalDateTime dueAt);

    int refreshPlanned(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("workflowVersionId") long workflowVersionId,
        @Param("workflowRuntimeGeneration") long workflowRuntimeGeneration,
        @Param("scheduledLocalDate") java.time.LocalDate scheduledLocalDate,
        @Param("dueAt") LocalDateTime dueAt);

    int markMissed(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("resolvedAt") LocalDateTime resolvedAt);

    Long findDuePlannedIdForUpdate(@Param("workspaceId") int workspaceId);

    int markQueued(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("queuedAt") LocalDateTime queuedAt);

    int markEnrolled(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("workflowRunId") long workflowRunId,
        @Param("resolvedAt") LocalDateTime resolvedAt);

    int restoreQueuedToPlanned(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int markQueuedMissed(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("resolvedAt") LocalDateTime resolvedAt);

    int markQueuedMissedNow(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);
}
