package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ReportSchedule;
import ooo.klae.connex.backend.dto.ReportScheduleRef;

/**
 * Data access for workspace-scoped report delivery schedules. The due reference
 * query is catalog-local and is used only while the scheduler pins that catalog.
 */
public interface ScheduleMapper {
    ReportSchedule getByReport(
            @Param("workspaceId") int workspaceId,
            @Param("reportDefinitionId") int reportDefinitionId);

    ReportSchedule getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    ReportSchedule lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insert(ReportSchedule schedule);

    int update(ReportSchedule schedule);

    int deleteByReport(
            @Param("workspaceId") int workspaceId,
            @Param("reportDefinitionId") int reportDefinitionId);

    List<ReportScheduleRef> dueScheduleRefs(@Param("now") LocalDateTime now);

    int markClaimed(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("lastRunAt") LocalDateTime lastRunAt);

    int markSkipped(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("nextRunAt") LocalDateTime nextRunAt);
}
