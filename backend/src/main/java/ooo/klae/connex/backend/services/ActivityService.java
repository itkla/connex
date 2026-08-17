package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.CountDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for logging and retrieving {@code Activity} records.
 * Every read/write is scoped to the caller's active workspace.
 * Delegates persistence to {@code ActivityMapper}, resolves inline @/# references
 * in the activity notes via {@code ReferenceService}, and dispatches member-mention
 * notifications.
 *
 * <p>Logging an activity against a contact is what stops that contact's first-response SLA clock
 * (#559): an activity is the record of the workspace having touched the lead. Interactions written
 * by provider capture and bulk history import do not pass through here and therefore do not stop
 * the clock — those paths do not model direction, so counting them would let the lead's own inbound
 * message satisfy the workspace's own response deadline.
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
    private final LeadResponseSlaService leadResponseSlaService;
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

    public List<ActivityVolumeBucketDto> getActivityVolume(int days, MemberScope memberScope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int bucketCount = activityBucketCount(days);
        double spanDays = days / (double) bucketCount;
        List<ActivityVolumeBucketDto> volume = new ArrayList<>(bucketCount);
        for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
            volume.add(new ActivityVolumeBucketDto(bucketIndex, 0, 0, 0, 0, 0, null));
        }
        for (ActivityVolumeBucketDto bucket :
                activityMapper.activityVolume(workspaceId, days, bucketCount, spanDays, memberScope)) {
            volume.set(bucket.bucketIndex(), bucket);
        }
        return volume;
    }

    public List<ActivityVolumeBucketDto> getActivityVolume(
            Window window, List<AnalyticsPeriod> periods, MemberScope memberScope) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<ActivityVolumeBucketDto> volume = new ArrayList<>(periods.size());
        for (AnalyticsPeriod period : periods) {
            volume.add(new ActivityVolumeBucketDto(
                period.index(), 0, 0, 0, 0, 0, period.startDate().toString()));
        }
        for (ActivityVolumeBucketDto bucket : activityMapper.activityVolumeByBoundaries(
                workspaceId, window.startUtc(), window.endUtc(), periods, memberScope)) {
            volume.set(bucket.bucketIndex(), bucket);
        }
        return volume;
    }

    public List<TeamLeaderboardEntryDto> getTeamLeaderboard(int days) {
        return activityMapper.teamLeaderboard(workspaceService.getCurrentWorkspaceId(), days);
    }

    public List<TeamLeaderboardEntryDto> getTeamLeaderboard(Window window) {
        return activityMapper.teamLeaderboardWindow(
            workspaceService.getCurrentWorkspaceId(), window.startUtc(), window.endUtc());
    }

    public CountDto getUpcomingCount(int days) {
        return new CountDto(activityMapper.upcomingCount(workspaceService.getCurrentWorkspaceId(), days));
    }

    public List<Activity> getActivitiesByPersonId(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(workspaceId,
            activityMapper.getActivitiesByPersonId(workspaceId, personId));
    }

    /** Returns a bounded, hydrated activity window for one workspace-scoped person. */
    public List<Activity> getActivitiesByPersonIdInWindow(
            int personId, LocalDateTime startUtc, LocalDateTime endUtc, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Activity window limit must be positive");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return referenceService.hydrateActivities(
            workspaceId,
            activityMapper.getActivitiesByPersonIdInWindow(
                workspaceId, personId, startUtc, endUtc, limit));
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
            activity.setType(normalizeType(activity.getType()));
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
        if (activity.getPerson() != null) {
            leadResponseSlaService.recordFirstResponse(workspaceId, activity.getPerson().getId());
        }
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
        requireEditable(before);
        User actor = authService.getCurrentUser();
        activity.setId(id);
        activity.setWorkspaceId(workspaceId);
        activity.setCreatedBy(before.getCreatedBy());
        activity.setType(normalizeType(activity.getType()));
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
        requireEditable(before);
        activityMapper.delete(workspaceId, id);
        referenceService.deleteReferences(workspaceId, ReferenceService.SOURCE_ACTIVITY, id);
        auditService.record("activity.delete", "activity", id, before.getSubject(),
            "Deleted activity " + before.getSubject(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /** Deletes an activity only when its locked current state satisfies the supplied guard. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.ACTIVITY_DELETE)
    public void deleteIf(int id, Predicate<Activity> guard) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Activity before = activityMapper.getActivityByIdForUpdate(workspaceId, id);
        if (before == null) {
            throw new ResourceNotFoundException("Activity not found with id: " + id);
        }
        requireEditable(before);
        if (guard == null || !guard.test(before)) {
            throw new ConflictException("Activity changed and cannot be deleted");
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

    private static void requireEditable(Activity activity) {
        if (activity.isProviderOwned()) {
            throw new ConflictException(
                "Captured provider evidence is read-only; change its capture decision instead");
        }
    }

    private void requireDealExists(int workspaceId, Integer dealId) {
        if (dealId != null && !dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
    }

    private static String normalizeType(String type) {
        return type == null ? null : type.trim();
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
