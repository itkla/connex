package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Activity;

import java.util.List;

/**
 * mapper interface for {@code Activity} persistence.
 * Used by {@code ActivityService}.
 */

public interface ActivityMapper {
    List<Activity> getAllActivities(int workspaceId);
    List<Activity> getActivitiesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Activity> getActivitiesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Activity> getActivitiesByCreatedById(@Param("workspaceId") int workspaceId, @Param("createdById") int createdById);
    Activity getActivityById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Activity> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Activity activity);
    int update(Activity activity);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
