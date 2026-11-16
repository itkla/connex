package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.MyWorkRelationshipsDto.Counts;
import ooo.klae.connex.backend.dto.NoteActivityDayDto;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/** Fixed-size personal projections; identity and workspace are supplied only by the service. */
public interface MyWorkReadMapper {
    List<Task> recentTasks(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<Activity> recentActivities(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<NoteActivityDayDto> activityPulse(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    Counts relationshipCounts(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("reference") LocalDateTime reference, @Param("model") SqlParameters model);
    List<Integer> relationshipIds(@Param("workspaceId") int workspaceId, @Param("userId") int userId,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson,
            @Param("reference") LocalDateTime reference, @Param("model") SqlParameters model,
            @Param("coolingOnly") boolean coolingOnly, @Param("limit") int limit);
}
