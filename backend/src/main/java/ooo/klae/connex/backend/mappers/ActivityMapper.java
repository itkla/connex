package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Activity;

import java.util.List;

/**
 * mapper interface for {@code Activity} persistence.
 * Used by {@code ActivityService}.
 */

public interface ActivityMapper {
    List<Activity> getAllActivities();
    List<Activity> getActivitiesByPersonId(int personId);
    List<Activity> getActivitiesByDealId(int dealId);
    List<Activity> getActivitiesByCreatedById(int createdById);
    Activity getActivityById(int id);
    int insert(Activity activity);
    int update(Activity activity);
    int delete(int id);
}
