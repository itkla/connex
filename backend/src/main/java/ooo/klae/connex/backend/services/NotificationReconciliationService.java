package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
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
    static final String RELATIONSHIP_TYPE = "relationship.cooling";
    static final String WARNING = "warning";
    static final String CRITICAL = "critical";
    private static final String COLD_BAND = "cold";
    private static final String COOLING_TREND = "cooling";
    private static final double HIGH_VALUE_PERCENTILE = 0.75;
    private static final int MIN_DEALS_FOR_VALUE_RANK = 4;
    private static final double LATE_STAGE_FRACTION = 0.75;
    private static final Set<String> KEY_ROLE_KEYWORDS = Set.of("champion", "decision", "buyer", "sponsor");

    private static final String IN_APP = "in_app";
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(NotificationReconciliationService.class);

    private final NotificationMapper notificationMapper;
    private final PreferenceMapper preferenceMapper;
    private final NotificationDispatcher dispatcher;
    private final NotificationProperties properties;
    private final ScoringService scoringService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * Reconciles a workspace's reminder notifications. The {@code includeRelationshipNudges} flag
     * gates the relationship-decay pass, which rescores the whole workspace and is therefore run
     * only by the scheduled sweep; the per-mutation source-change path skips it, since decay is
     * time-driven and the next sweep picks it up within the reconciliation interval.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileWorkspace(int workspaceId, boolean includeRelationshipNudges) {
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

        if (includeRelationshipNudges) {
            try {
                addRelationshipNudges(workspaceId, existing, expected, preferences, triggeredAt);
            } catch (RuntimeException exception) {
                log.warn("Relationship-nudge reconciliation failed for workspace={}", workspaceId, exception);
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

    private void addRelationshipNudges(
        int workspaceId,
        Map<ReminderKey, Notification> existing,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt
    ) {
        List<RelationshipNudgeCandidate> nudgeCandidates =
            notificationMapper.findRelationshipNudgeCandidates(workspaceId);
        if (nudgeCandidates.isEmpty()) {
            return;
        }
        Map<Integer, RelationshipTemperatureDto> temperatures = scoreByPerson(workspaceId);
        double highValueThreshold = highValueThreshold(notificationMapper.findOpenDealValues(workspaceId));
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (RelationshipNudgeCandidate candidate : nudgeCandidates) {
            RelationshipTemperatureDto temperature = temperatures.get(candidate.getPersonId());
            if (temperature == null) {
                continue;
            }
            String dedupeKey =
                "relationship.cooling:" + candidate.getDealId() + ":" + candidate.getPersonId();
            ReminderKey key = new ReminderKey(workspaceId, candidate.getRecipientId(), dedupeKey);
            String severity = nudgeSeverity(
                temperature.getBand(),
                temperature.getTrend(),
                temperature.getDaysSinceTouch(),
                properties.getCoolingMinDaysSinceTouch(),
                properties.getCoolingBackfillDays(),
                existing.containsKey(key)
            );
            if (severity == null || !enabled(preferences, candidate.getRecipientId(), RELATIONSHIP_TYPE)) {
                continue;
            }
            List<String> reasons = priorityReasons(
                candidate, highValueThreshold, today, properties.getCoolingCloseSoonDays());
            if (WARNING.equals(severity) && !reasons.isEmpty()) {
                severity = CRITICAL;
            }
            expected.put(key, relationshipNudgeNotification(
                candidate, temperature, severity, reasons, dedupeKey, triggeredAt));
        }
    }

    /**
     * Reasons a decaying relationship's nudge is high-priority: a soon-closing, high-value, or
     * late-stage deal, or a named key stakeholder. A {@code warning}-level nudge with any reason is
     * escalated to {@code critical}; the reasons also ride in the notification payload.
     */
    static List<String> priorityReasons(
        RelationshipNudgeCandidate candidate,
        double highValueThreshold,
        LocalDate today,
        int closeSoonDays
    ) {
        List<String> reasons = new ArrayList<>();
        if (closingSoon(candidate.getExpectedCloseDate(), today, closeSoonDays)) {
            reasons.add("closing_soon");
        }
        if (candidate.getDealValue() > highValueThreshold) {
            reasons.add("high_value");
        }
        if (lateStage(candidate.getStagePosition(), candidate.getPipelineMaxPosition())) {
            reasons.add("late_stage");
        }
        if (keyRole(candidate.getPersonRole())) {
            reasons.add("key_role");
        }
        return reasons;
    }

    private static boolean closingSoon(String expectedCloseDate, LocalDate today, int closeSoonDays) {
        if (expectedCloseDate == null || expectedCloseDate.isBlank()) {
            return false;
        }
        try {
            return !LocalDate.parse(expectedCloseDate).isAfter(today.plusDays(Math.max(0, closeSoonDays)));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static boolean lateStage(Integer position, Integer maxPosition) {
        if (position == null || maxPosition == null || maxPosition <= 0) {
            return false;
        }
        return position >= maxPosition * LATE_STAGE_FRACTION;
    }

    private static boolean keyRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        return KEY_ROLE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /**
     * The deal value at or above which a deal counts as "high value" for nudge weighting — the
     * {@link #HIGH_VALUE_PERCENTILE} of the workspace's open-deal values (nearest-rank). Returns
     * {@link Double#POSITIVE_INFINITY} when there are too few deals to rank meaningfully, which
     * leaves the value signal off rather than flagging an arbitrary deal as high-value.
     */
    static double highValueThreshold(List<Double> openDealValues) {
        if (openDealValues == null || openDealValues.size() < MIN_DEALS_FOR_VALUE_RANK) {
            return Double.POSITIVE_INFINITY;
        }
        List<Double> sorted = openDealValues.stream().sorted().toList();
        int rank = (int) Math.ceil(HIGH_VALUE_PERCENTILE * sorted.size());
        int index = Math.max(1, Math.min(sorted.size(), rank)) - 1;
        return sorted.get(index);
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

    /**
     * Severity for a relationship-decay nudge, or {@code null} when the contact does not yet
     * warrant one. A contact that has gone {@code cold} is {@link #CRITICAL}; one that is still
     * warmer but {@code cooling} is {@link #WARNING}. Both require the relationship to have been
     * quiet for at least {@code minDaysSinceTouch} days, which keeps a freshly-followed-up contact
     * from being nagged and excludes never-touched stakeholders (their days-since is {@code null}).
     *
     * <p>A <em>new</em> nudge is additionally capped at {@code backfillDaysSinceTouch}: a contact
     * quiet beyond that window is not flagged for the first time, so a workspace adopting Connex
     * with long-dormant relationships does not flood inboxes on the first sweep. An existing nudge
     * ({@code reminderExists}) is kept past the cap and resolves only on warm-up or deal close.
     */
    static String nudgeSeverity(
        String band,
        String trend,
        Integer daysSinceTouch,
        int minDaysSinceTouch,
        int backfillDaysSinceTouch,
        boolean reminderExists
    ) {
        if (daysSinceTouch == null || daysSinceTouch < minDaysSinceTouch) {
            return null;
        }
        if (!reminderExists && daysSinceTouch > backfillDaysSinceTouch) {
            return null;
        }
        if (COLD_BAND.equals(band)) {
            return CRITICAL;
        }
        return COOLING_TREND.equals(trend) ? WARNING : null;
    }

    private Map<Integer, RelationshipTemperatureDto> scoreByPerson(int workspaceId) {
        Map<Integer, RelationshipTemperatureDto> temperatures = new HashMap<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
            temperatures.put(temperature.getId(), temperature);
        }
        return temperatures;
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

    private Notification relationshipNudgeNotification(
        RelationshipNudgeCandidate candidate,
        RelationshipTemperatureDto temperature,
        String severity,
        List<String> priorityReasons,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = base(
            candidate.getWorkspaceId(),
            candidate.getRecipientId(),
            RELATIONSHIP_TYPE,
            "relationship",
            severity,
            "person",
            candidate.getPersonId(),
            candidate.getPersonLabel(),
            dedupeKey,
            triggeredAt
        );
        boolean cold = COLD_BAND.equals(temperature.getBand());
        notification.setContextType("deal");
        notification.setContextId(candidate.getDealId());
        notification.setContextLabel(candidate.getDealLabel());
        notification.setTitle(cold ? "Relationship gone cold" : "Relationship cooling");
        notification.setBody(candidate.getPersonLabel() + " on " + candidate.getDealLabel()
            + " — " + temperature.getDaysSinceTouch() + " days since last contact");
        notification.setActionUrl("/records/deals/" + candidate.getDealId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("person", candidate.getPersonLabel());
        data.put("deal", candidate.getDealLabel());
        data.put("personId", candidate.getPersonId());
        data.put("dealId", candidate.getDealId());
        data.put("daysSinceTouch", temperature.getDaysSinceTouch());
        data.put("band", temperature.getBand());
        data.put("trend", temperature.getTrend());
        data.put("dealValue", candidate.getDealValue());
        if (candidate.getExpectedCloseDate() != null) {
            data.put("expectedCloseDate", candidate.getExpectedCloseDate());
        }
        if (candidate.getPersonRole() != null && !candidate.getPersonRole().isBlank()) {
            data.put("role", candidate.getPersonRole());
        }
        if (!priorityReasons.isEmpty()) {
            data.put("priorityReasons", priorityReasons);
        }
        notification.setData(json(data));
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