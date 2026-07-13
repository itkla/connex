package ooo.klae.connex.backend.mappers;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ReportGoal;

/**
 * Data access for workspace-scoped report goals. Every statement is explicitly
 * constrained by {@code workspaceId}; owner labels are hydrated in the service.
 */
public interface GoalMapper {
    List<ReportGoal> getGoals(@Param("workspaceId") int workspaceId);

    ReportGoal getGoal(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<ReportGoal> getGoalsForPeriod(
            @Param("workspaceId") int workspaceId,
            @Param("metric") String metric,
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDate periodStart);

    void insert(ReportGoal goal);

    int update(ReportGoal goal);

    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
