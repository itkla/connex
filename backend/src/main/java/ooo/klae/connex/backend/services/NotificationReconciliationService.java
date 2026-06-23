package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.notifications.NotificationDispatcher;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes expected reminders from workspace-scoped source projections.
 */
@Service
@RequiredArgsConstructor
public class NotificationReconciliationService {
    static final String TASK_TYPE = "task.due";
    static final String DEAL_TYPE = "deal.close";
    static final String WARNING = "warning";
    static final String CRITICAL = "critical";

    private static final String IN_APP = "in_app";
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationMapper notificationMapper;
    private final PreferenceMapper preferenceMapper;
    private final NotificationDispatcher dispatcher;
    private final NotificationProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileWorkspace(int workspaceId) {
        String triggeredAt = utcTimestamp(clock.instant());
        Map<ReminderKey, Notification> existing = loadExisting(workspaceId);
        Map<PreferenceKey, Boolean> preferences = loadPreferences(workspaceId);
        Map<ReminderKey, Notification> expected = new LinkedHashMap<>();

        for (TaskReminderCandidate candidate : notificationMapper.findTaskReminderCandidates(workspaceId)) {
            String dedupeKey = "task.due:" + candidate.getTaskId();
            ReminderKey key = new ReminderKey(workspaceId, candidate.getRecipientId(), dedupeKey);
            LocalDate today = LocalDate.now(clock.withZone(zone(candidate.getRecipientTimezone())));
            String severity = classify(
                LocalDate.parse(candidate.getDueDate()),
                today,
                1,
                properties.getOverdueBackfillDays(),
                existing.containsKey(key)
            );
            if (severity != null && enabled(preferences, candidate.getRecipientId(), TASK_TYPE)) {
                expected.put(key, taskNotification(candidate, severity, dedupeKey, triggeredAt));
            }
        }

        for (DealReminderCandidate candidate : notificationMapper.findDealReminderCandidates(workspaceId)) {
            String dedupeKey = "deal.close:" + candidate.getDealId();
            ReminderKey key = new ReminderKey(workspaceId, candidate.getRecipientId(), dedupeKey);
            LocalDate today = LocalDate.now(clock.withZone(zone(candidate.getRecipientTimezone())));
            String severity = classify(
                LocalDate.parse(candidate.getExpectedCloseDate()),
                today,
                7,
                properties.getOverdueBackfillDays(),
                existing.containsKey(key)
            );
            if (severity != null && enabled(preferences, candidate.getRecipientId(), DEAL_TYPE)) {
                expected.put(key, dealNotification(candidate, severity, dedupeKey, triggeredAt));
            }
        }

        expected.values().forEach(dispatcher::dispatch);
        for (Map.Entry<ReminderKey, Notification> entry : existing.entrySet()) {
            Notification notification = entry.getValue();
            if (notification.getResolvedAt() == null && !expected.containsKey(entry.getKey())) {
                notificationMapper.resolveReminder(
                    workspaceId,
                    notification.getRecipientId(),
                    notification.getId(),
                    triggeredAt
                );
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeWorkspace(int workspaceId) {
        int retentionDays = Math.max(1, properties.getRetentionDays());
        String cutoff = utcTimestamp(clock.instant().minusSeconds(retentionDays * 86_400L));
        return notificationMapper.purgeWorkspaceReminderHistory(workspaceId, cutoff);
    }

    static String classify(
        LocalDate dueDate,
        LocalDate today,
        int warningDays,
        int overdueBackfillDays,
        boolean reminderExists
    ) {
        if (dueDate.isBefore(today)) {
            LocalDate oldestInitialDate = today.minusDays(Math.max(0, overdueBackfillDays));
            return reminderExists || !dueDate.isBefore(oldestInitialDate) ? CRITICAL : null;
        }
        return dueDate.isAfter(today.plusDays(warningDays)) ? null : WARNING;
    }

    private Map<ReminderKey, Notification> loadExisting(int workspaceId) {
        Map<ReminderKey, Notification> existing = new LinkedHashMap<>();
        for (Notification notification : notificationMapper.findWorkspaceReminderNotifications(workspaceId)) {
            existing.put(
                new ReminderKey(workspaceId, notification.getRecipientId(), notification.getDedupeKey()),
                notification
            );
        }
        return existing;
    }

    private Map<PreferenceKey, Boolean> loadPreferences(int workspaceId) {
        Map<PreferenceKey, Boolean> preferences = new LinkedHashMap<>();
        for (NotificationPreference preference :
                preferenceMapper.findByWorkspaceAndChannel(workspaceId, IN_APP)) {
            preferences.put(
                new PreferenceKey(preference.getUserId(), preference.getType()),
                preference.isEnabled()
            );
        }
        return preferences;
    }

    private static boolean enabled(
        Map<PreferenceKey, Boolean> preferences,
        int recipientId,
        String type
    ) {
        Boolean exact = preferences.get(new PreferenceKey(recipientId, type));
        if (exact != null) {
            return exact;
        }
        return preferences.getOrDefault(new PreferenceKey(recipientId, "*"), true);
    }

    private Notification taskNotification(
        TaskReminderCandidate candidate,
        String severity,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = base(
            candidate.getWorkspaceId(),
            candidate.getRecipientId(),
            TASK_TYPE,
            "task",
            severity,
            "task",
            candidate.getTaskId(),
            candidate.getTaskLabel(),
            dedupeKey,
            triggeredAt
        );
        if (candidate.getDealId() != null) {
            notification.setContextType("deal");
            notification.setContextId(candidate.getDealId());
            notification.setContextLabel(candidate.getDealLabel());
        } else if (candidate.getPersonId() != null) {
            notification.setContextType("person");
            notification.setContextId(candidate.getPersonId());
            notification.setContextLabel(candidate.getPersonLabel());
        } else {
            notification.setContextType(null);
            notification.setContextId(null);
        }
        notification.setTitle(CRITICAL.equals(severity) ? "Task overdue" : "Task due soon");
        notification.setBody(candidate.getTaskLabel() + " — Due " + candidate.getDueDate());
        notification.setActionUrl("/activity/tasks?taskId=" + candidate.getTaskId());
        notification.setData(json(Map.of(
            "taskId", candidate.getTaskId(),
            "task", candidate.getTaskLabel(),
            "dueDate", candidate.getDueDate()
        )));
        return notification;
    }

    private Notification dealNotification(
        DealReminderCandidate candidate,
        String severity,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = base(
            candidate.getWorkspaceId(),
            candidate.getRecipientId(),
            DEAL_TYPE,
            "deal",
            severity,
            "deal",
            candidate.getDealId(),
            candidate.getDealLabel(),
            dedupeKey,
            triggeredAt
        );
        notification.setContextType("deal");
        notification.setContextId(candidate.getDealId());
        notification.setContextLabel(candidate.getDealLabel());
        notification.setTitle(CRITICAL.equals(severity) ? "Deal close date overdue" : "Deal closing soon");
        notification.setBody(candidate.getDealLabel() + " — Expected " + candidate.getExpectedCloseDate());
        notification.setActionUrl("/records/deals/" + candidate.getDealId());
        notification.setData(json(Map.of(
            "dealId", candidate.getDealId(),
            "deal", candidate.getDealLabel(),
            "expectedCloseDate", candidate.getExpectedCloseDate()
        )));
        return notification;
    }

    private static Notification base(
        int workspaceId,
        int recipientId,
        String type,
        String category,
        String severity,
        String sourceType,
        int sourceId,
        String sourceLabel,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setRecipientId(recipientId);
        notification.setType(type);
        notification.setCategory(category);
        notification.setSeverity(severity);
        notification.setTemplateVersion(1);
        notification.setSourceType(sourceType);
        notification.setSourceId(sourceId);
        notification.setSourceLabel(sourceLabel);
        notification.setDedupeKey(dedupeKey);
        notification.setTriggeredAt(triggeredAt);
        return notification;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }

    private static ZoneId zone(String timezone) {
        return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
    }

    private static String utcTimestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(MYSQL_DATETIME);
    }

    private record ReminderKey(int workspaceId, int recipientId, String dedupeKey) {}

    private record PreferenceKey(int recipientId, String type) {}
}