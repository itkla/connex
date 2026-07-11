package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;

import java.util.List;

/**
 * mapper interface for {@code Activity} persistence.
 * Used by {@code ActivityService}.
 */

public interface ActivityMapper {
    List<Activity> getAllActivities(int workspaceId);
    List<Activity> getActivitiesPage(@Param("workspaceId") int workspaceId, @Param("limit") int limit, @Param("offset") int offset);
    List<Activity> getActivitiesFilteredPage(
        @Param("workspaceId") int workspaceId,
        @Param("personId") Integer personId,
        @Param("dealId") Integer dealId,
        @Param("createdById") Integer createdById,
        @Param("limit") int limit,
        @Param("offset") int offset);
    long countActivities(
        @Param("workspaceId") int workspaceId,
        @Param("personId") Integer personId,
        @Param("dealId") Integer dealId,
        @Param("createdById") Integer createdById);
    List<ActivityVolumeBucketDto> activityVolume(
        @Param("workspaceId") int workspaceId,
        @Param("days") int days,
        @Param("buckets") int buckets,
        @Param("spanDays") double spanDays
    );
    List<TeamLeaderboardEntryDto> teamLeaderboard(
        @Param("workspaceId") int workspaceId,
        @Param("days") int days
    );
    long upcomingCount(@Param("workspaceId") int workspaceId, @Param("days") int days);
    List<Activity> getActivitiesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Activity> getActivitiesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Activity> getActivitiesByCreatedById(@Param("workspaceId") int workspaceId, @Param("createdById") int createdById);
    Activity getActivityById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Activity> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Activity activity);
    int update(Activity activity);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Counts activities created by a user across all workspaces. Service-layer
     * mirror of the {@code activity.created_by_id} ON DELETE RESTRICT (#440
     * increment 3).
     */
    int countCreatedAnywhere(@Param("userId") int userId);
}
