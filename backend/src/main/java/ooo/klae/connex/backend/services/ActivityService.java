package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Activity} records.
 * Every read/write is scoped to the caller's active workspace.
 * Delegates persistence to {@code ActivityMapper}.
 */

@Service
@RequiredArgsConstructor
public class ActivityService {
    private final ActivityMapper activityMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("type", "subject", "notes", "timestamp");

    public List<Activity> getAllActivities() {
        return activityMapper.getAllActivities(workspaceService.getCurrentWorkspaceId());
    }

    public List<Activity> getActivitiesByPersonId(int personId) {
        return activityMapper.getActivitiesByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    public List<Activity> getActivitiesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return activityMapper.getActivitiesByDealId(workspaceId, dealId);
    }

    public List<Activity> getActivitiesByCreatedById(int createdById) {
        return activityMapper.getActivitiesByCreatedById(workspaceService.getCurrentWorkspaceId(), createdById);
    }

    /**
     * Retrieves a workspace-scoped activity by ID, throwing if absent.
     */
    public Activity getActivityById(int id) {
        Activity activity = activityMapper.getActivityById(workspaceService.getCurrentWorkspaceId(), id);
        if (activity == null) throw new ResourceNotFoundException("Activity not found with id: " + id);
        return activity;
    }

    /**
     * Creates a new activity in the active workspace.
     */
    public Activity create(Activity activity) {
        workspaceService.requirePermission(Permission.ACTIVITY_CREATE);
        try {
            activity.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
            activityMapper.insert(activity);
            auditService.record("activity.create", "activity", activity.getId(), activity.getSubject(),
                    "Created activity " + activity.getSubject(),
                    auditService.diff(null, activity, AUDIT_FIELDS));
            return activity;
        } catch (Exception e) {
            auditService.recordFailure("activity.create", "activity", null, activity.getSubject(),
                    "Failed to create activity", e.getMessage());
            throw e;
        }
    }

    /**
     * Updates a workspace-scoped activity.
     */
    public Activity update(int id, Activity activity) {
        workspaceService.requirePermission(Permission.ACTIVITY_UPDATE);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity before = activityMapper.getActivityById(workspaceId, id);
        if (before == null) {
            auditService.recordFailure("activity.update", "activity", id, null,
                    "Could not update activity because it was not found", null);
            throw new ResourceNotFoundException("Activity not found with id: " + id);
        }
        activity.setId(id);
        activity.setWorkspaceId(workspaceId);
        activityMapper.update(activity);
        auditService.record("activity.update", "activity", id, activity.getSubject(),
            "Updated activity " + activity.getSubject(),
            auditService.diff(before, activity, AUDIT_FIELDS));
        return activity;
    }

    /**
     * Deletes a workspace-scoped activity.
     */
    public void delete(int id) {
        workspaceService.requirePermission(Permission.ACTIVITY_DELETE);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity before = activityMapper.getActivityById(workspaceId, id);
        if (before == null) {
            auditService.recordFailure("activity.delete", "activity", id, null,
                    "Could not delete activity because it was not found", null);
            throw new ResourceNotFoundException("Activity not found with id: " + id);
        }
        activityMapper.delete(workspaceId, id);
        auditService.record("activity.delete", "activity", id, before.getSubject(),
            "Deleted activity " + before.getSubject(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}
