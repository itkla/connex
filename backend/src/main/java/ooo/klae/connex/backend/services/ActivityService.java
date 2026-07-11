package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.CountDto;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Activity} records.
 * Every read/write is scoped to the caller's active workspace.
 * Delegates persistence to {@code ActivityMapper}, resolves inline @/# references
 * in the activity notes via {@code ReferenceService}, and dispatches member-mention
 * notifications.
 */

@Service
@RequiredArgsConstructor
public class ActivityService {
    private final ActivityMapper activityMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ReferenceService referenceService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final ObjectMapper objectMapper;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("type", "subject", "notes", "timestamp");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String MENTION_TYPE = "activity.mention";
    private static final String MENTION_CATEGORY = "activity";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;

    public List<Activity> getAllActivities() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId, activityMapper.getAllActivities(workspaceId));
    }

    public List<Activity> getActivitiesPage(int limit, int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesPage(workspaceId, limit, offset));
    }

    public List<Activity> getActivitiesPage(Integer personId, Integer dealId, Integer createdById, int limit, int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDealExists(workspaceId, dealId);
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesFilteredPage(workspaceId, personId, dealId, createdById, limit, offset));
    }

    public long countActivities(Integer personId, Integer dealId, Integer createdById) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDealExists(workspaceId, dealId);
        return activityMapper.countActivities(workspaceId, personId, dealId, createdById);
    }

    public List<ActivityVolumeBucketDto> getActivityVolume(int days) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bucketCount = activityBucketCount(days);
        double spanDays = days / (double) bucketCount;
        List<ActivityVolumeBucketDto> volume = new ArrayList<>(bucketCount);
        for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
            volume.add(new ActivityVolumeBucketDto(bucketIndex, 0, 0, 0, 0, 0));
        }
        for (ActivityVolumeBucketDto bucket :
                activityMapper.activityVolume(workspaceId, days, bucketCount, spanDays)) {
            volume.set(bucket.bucketIndex(), bucket);
        }
        return volume;
    }

    public List<TeamLeaderboardEntryDto> getTeamLeaderboard(int days) {
        return activityMapper.teamLeaderboard(workspaceService.getCurrentWorkspaceId(), days);
    }

    public CountDto getUpcomingCount(int days) {
        return new CountDto(activityMapper.upcomingCount(workspaceService.getCurrentWorkspaceId(), days));
    }

    public List<Activity> getActivitiesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByPersonId(workspaceId, personId));
    }

    private static int activityBucketCount(int days) {
        return switch (days) {
            case 30 -> 6;
            case 90 -> 9;
            case 365 -> 12;
            default -> throw new IllegalArgumentException("Unsupported analytics range: " + days);
        };
    }

    public List<Activity> getActivitiesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDealExists(workspaceId, dealId);
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByDealId(workspaceId, dealId));
    }

    public List<Activity> getActivitiesByCreatedById(int createdById) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByCreatedById(workspaceId, createdById));
    }

    /**
     * Retrieves a workspace-scoped activity by ID, throwing if absent.
     */
    public Activity getActivityById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity activity = activityMapper.getActivityById(workspaceId, id);
        if (activity == null) throw new ResourceNotFoundException("Activity not found with id: " + id);
        return hydrate(workspaceId, activity);
    }

    /**
     * Creates a new activity in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.ACTIVITY_CREATE)
    public Activity create(Activity activity) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User actor = authService.getCurrentUser();
        try {
            activity.setWorkspaceId(workspaceId);
            activity.setCreatedBy(actor);
            activity.setTimestamp(resolveTimestamp(activity.getTimestamp(), null));
            activityMapper.insert(activity);
            auditService.record("activity.create", "activity", activity.getId(), activity.getSubject(),
                    "Created activity " + activity.getSubject(),
                    auditService.diff(null, activity, AUDIT_FIELDS));
        } catch (Exception e) {
            auditService.recordFailure("activity.create", "activity", null, activity.getSubject(),
                    "Failed to create activity", e.getMessage());
            throw e;
        }
        List<Integer> mentioned = referenceService.syncReferences(
            workspaceId, ReferenceService.SOURCE_ACTIVITY, activity.getId(), activity.getNotes());
        notifyMentions(workspaceId, activity, mentioned, actor);
        return hydrate(workspaceId, activity);
    }

    /**
     * Updates a workspace-scoped activity.
     */
    @Transactional
    @RequirePermission(Permission.ACTIVITY_UPDATE)
    public Activity update(int id, Activity activity) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity before = activityMapper.getActivityById(workspaceId, id);
        if (before == null) {
            auditService.recordFailure("activity.update", "activity", id, null,
                    "Could not update activity because it was not found", null);
            throw new ResourceNotFoundException("Activity not found with id: " + id);
        }
        User actor = authService.getCurrentUser();
        activity.setId(id);
        activity.setWorkspaceId(workspaceId);
        activity.setCreatedBy(before.getCreatedBy());
        activity.setTimestamp(resolveTimestamp(activity.getTimestamp(), before.getTimestamp()));
        activityMapper.update(activity);
        auditService.record("activity.update", "activity", id, activity.getSubject(),
            "Updated activity " + activity.getSubject(),
            auditService.diff(before, activity, AUDIT_FIELDS));
        List<Integer> mentioned = referenceService.syncReferences(
            workspaceId, ReferenceService.SOURCE_ACTIVITY, id, activity.getNotes());
        notifyMentions(workspaceId, activity, mentioned, actor);
        return hydrate(workspaceId, activity);
    }

    /**
     * Deletes a workspace-scoped activity.
     */
    @RequirePermission(Permission.ACTIVITY_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity before = activityMapper.getActivityById(workspaceId, id);
        if (before == null) {
            auditService.recordFailure("activity.delete", "activity", id, null,
                    "Could not delete activity because it was not found", null);
            throw new ResourceNotFoundException("Activity not found with id: " + id);
        }
        activityMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_ACTIVITY, id);
        auditService.record("activity.delete", "activity", id, before.getSubject(),
            "Deleted activity " + before.getSubject(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    private Activity hydrate(int workspaceId, Activity activity) {
        return referenceService.hydrateActivities(workspaceId, List.of(activity)).get(0);
    }

    private void requireDealExists(int workspaceId, Integer dealId) {
        if (dealId != null && !dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
    }

    private void notifyMentions(int workspaceId, Activity activity, List<Integer> recipientIds, User actor) {
        if (recipientIds.isEmpty()) {
            return;
        }
        String snippet = snippet(activity.getNotes());
        String triggeredAt = LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMAT);
        String activityAnchor = "?activity=" + activity.getId();
        String contextType = null;
        Integer contextId = null;
        String actionUrl = "/activity/all" + activityAnchor;
        if (activity.getDeal() != null && activity.getDeal().getId() > 0) {
            contextType = "deal";
            contextId = activity.getDeal().getId();
            actionUrl = "/records/deals/" + contextId + activityAnchor;
        } else if (activity.getPerson() != null && activity.getPerson().getId() > 0) {
            contextType = "person";
            contextId = activity.getPerson().getId();
            actionUrl = "/records/contacts/" + contextId + activityAnchor;
        }
        for (int recipientId : recipientIds) {
            if (recipientId == actor.getId()) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(recipientId, MENTION_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(MENTION_TYPE);
                notification.setCategory(MENTION_CATEGORY);
                notification.setSeverity(MENTION_SEVERITY);
                notification.setTemplateVersion(1);
                notification.setTitle("New mention");
                notification.setBody(actor.getDisplayName() + " mentioned you in an activity");
                notification.setActorId(actor.getId());
                notification.setActorLabel(actor.getDisplayName());
                notification.setSourceType("activity");
                notification.setSourceId(activity.getId());
                notification.setSourceLabel(snippet);
                notification.setContextType(contextType);
                notification.setContextId(contextId);
                notification.setActionUrl(actionUrl);
                notification.setDedupeKey(MENTION_TYPE + ":" + activity.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("activityId", activity.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }

    private static String resolveTimestamp(String provided, String fallback) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }
}
