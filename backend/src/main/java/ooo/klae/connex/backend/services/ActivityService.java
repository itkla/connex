package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Activity} records.
 * Handles mapping between {@code ActivityDto} and {@code Activity} bean.
 * Delegates persistence to {@code ActivityMapper}.
 */

@Service
@RequiredArgsConstructor
public class ActivityService {
    private final ActivityMapper activityMapper;

    public List<Activity> getAllActivities() {
        return activityMapper.getAllActivities();
    }

    public List<Activity> getActivitiesByPersonId(int personId) {
        return activityMapper.getActivitiesByPersonId(personId);
    }

    public List<Activity> getActivitiesByDealId(int dealId) {
        return activityMapper.getActivitiesByDealId(dealId);
    }

    public List<Activity> getActivitiesByCreatedById(int createdById) {
        return activityMapper.getActivitiesByCreatedById(createdById);
    }

    /**
     * Retrieves a single activity by ID. Throws {@code ResourceNotFoundException} if no activity exists with the given ID.
     * @param id
     * @return
     */
    public Activity getActivityById(int id) {
        Activity activity = activityMapper.getActivityById(id);
        if (activity == null) throw new ResourceNotFoundException("Activity not found with id: " + id);
        return activity;
    }

    /**
     * Creates a new activity using the provided activity data.
     * @param activity
     * @return
     */
    public Activity create(Activity activity) {
        activityMapper.insert(activity);
        return activity;
    }

    /**
     * Updates the activity with the given id using the provided activity data.
     * @param id
     * @param activity
     * @return
     */
    public Activity update(int id, Activity activity) {
        if (activityMapper.getActivityById(id) == null) throw new ResourceNotFoundException("Activity not found with id: " + id);
        activity.setId(id);
        activityMapper.update(activity);
        return activity;
    }

    /**
     * Deletes the activity with the given id.
     * @param id
     */
    public void delete(int id) {
        if (activityMapper.getActivityById(id) == null) throw new ResourceNotFoundException("Activity not found with id: " + id);
        activityMapper.delete(id);
    }
}
