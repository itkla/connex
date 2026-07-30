package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.HistoricalNotificationBaseline;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.OpenDealRecipient;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes expected reminders from workspace-scoped source projections.
 */
@Service
@RequiredArgsConstructor
public class NotificationReconciliationService {
    static final String TASK_TYPE = "task.due";
    static final String DEAL_TYPE = "deal.close";
    static final String DEAL_RISK_TYPE = "deal.risk";
    static final String RELATIONSHIP_TYPE = "relationship.cooling";
    static final String INTRO_OPPORTUNITY_TYPE = "relationship.intro_opportunity";
    private static final String RISK_HIGH = "high";
    private static final String RISK_MEDIUM = "medium";
    private static final String INFO = "info";
    static final String WARNING = "warning";
    static final String CRITICAL = "critical";
    private static final String COLD_BAND = "cold";
    private static final String COOL_BAND = "cool";
    private static final String COOLING_TREND = "cooling";
    private static final double HIGH_VALUE_PERCENTILE = 0.75;
    private static final int MIN_DEALS_FOR_VALUE_RANK = 4;
    private static final double LATE_STAGE_FRACTION = 0.75;
    private static final Set<String> KEY_ROLE_KEYWORDS = Set.of("champion", "decision", "buyer", "sponsor");

    private static final String IN_APP = "in_app";
    private static final int BASELINE_BATCH_SIZE = 250;
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Logger log = LoggerFactory.getLogger(NotificationReconciliationService.class);

    private final NotificationMapper notificationMapper;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final PreferenceMapper preferenceMapper;
    private final NotificationDelivery notificationDelivery;
    private final NotificationStateVersionService stateVersionService;
    private final NotificationProperties properties;
    private final ScoringService scoringService;
    private final IntroductionService introductionService;
    private final DealRiskService dealRiskService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * Reconciles a workspace's reminder notifications. The {@code includeRelationshipNudges} flag
     * gates the relationship-decay passes, which rescore the whole workspace and are therefore run
     * only by the scheduled sweep; the per-mutation source-change path skips them, since decay is
     * time-driven and the next sweep picks it up within the reconciliation interval.
     *
     * <p>Every pass runs isolated and atomic (see
     * {@link #runManagedPass(int, Map, Set, String, boolean, boolean, String, Consumer)}): it stages
     * its notifications and merges them only on success, a failure is logged without aborting the
     * cycle, and the resolve pass clears only reminder types whose pass completed — resolving what
     * was never recomputed would clear valid reminders, and the next successful sweep would
     * resurrect them as unread, wiping read/dismiss state. The warmth rescore shared by the
     * relationship passes degrades the same way: when scoring fails, those passes are skipped and
     * their types left unmanaged while task and deal reminders still deliver. Delivery itself is
     * deliberately not isolated — an in-app dispatch failure aborts and rolls back the cycle so the
     * inbox is never half-written. Pass collaborators must not join this transaction with their own
     * {@code @Transactional}: a caught exception that crossed a joined proxy would mark the
     * transaction rollback-only and void this isolation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileWorkspace(int workspaceId, boolean includeRelationshipNudges) {
        duplicateDecisionLockService.lockBackgroundOrganization(workspaceId);
        Instant evaluationInstant = clock.instant();
        String triggeredAt = utcTimestamp(evaluationInstant);
        Map<ReminderKey, Notification> existing = loadExisting(workspaceId);
        List<HistoricalNotificationBaseline> baselines =
            notificationMapper.findHistoricalNotificationBaselines(workspaceId);
        Map<PreferenceKey, Boolean> preferences = loadPreferences(workspaceId);
        Map<ReminderKey, Notification> expected = new LinkedHashMap<>();
        Set<String> managedTypes = new HashSet<>();

        runManagedPass(workspaceId, expected, managedTypes, TASK_TYPE, true, true, "Task-reminder",
            staged -> addTaskReminders(
                workspaceId, existing, staged, preferences, triggeredAt, evaluationInstant));
        runManagedPass(workspaceId, expected, managedTypes, DEAL_TYPE, true, true, "Deal-close-reminder",
            staged -> addDealCloseReminders(
                workspaceId, existing, staged, preferences, triggeredAt, evaluationInstant));

        RelationshipSourceSnapshot relationshipSources =
            includeRelationshipNudges
                ? relationshipSourceSnapshot(workspaceId, evaluationInstant)
                : null;
        Map<Integer, RelationshipTemperatureDto> temperatures =
            relationshipSources == null
                ? null
                : relationshipSources.temperatures();
        Map<Integer, String> contactSourceStateHashes =
            relationshipSources == null
                ? Map.of()
                : relationshipSources.sourceStateHashes();
        Map<ReminderKey, String> dealRiskSourceStates = new LinkedHashMap<>();
        if (includeRelationshipNudges) {
            boolean scored = temperatures != null;
            runManagedPass(workspaceId, expected, managedTypes, RELATIONSHIP_TYPE, true, scored,
                "Relationship-nudge",
                staged -> addRelationshipNudges(
                    workspaceId,
                    existing,
                    staged,
                    preferences,
                    triggeredAt,
                    temperatures,
                    evaluationInstant));
            runManagedPass(workspaceId, expected, managedTypes, INTRO_OPPORTUNITY_TYPE,
                properties.isIntroOpportunitiesEnabled(), scored, "Intro-opportunity",
                staged -> addIntroOpportunities(workspaceId, staged, preferences, triggeredAt, temperatures));
            Map<ReminderKey, String> stagedDealRiskSourceStates =
                new LinkedHashMap<>();
            runManagedPass(workspaceId, expected, managedTypes, DEAL_RISK_TYPE,
                properties.isDealRiskEnabled(), scored, "Deal-risk",
                staged -> addDealRiskNotifications(
                    workspaceId,
                    staged,
                    preferences,
                    triggeredAt,
                    temperatures,
                    contactSourceStateHashes,
                    stagedDealRiskSourceStates));
            if (managedTypes.contains(DEAL_RISK_TYPE)
                    && properties.isDealRiskEnabled()
                    && scored) {
                dealRiskSourceStates.putAll(stagedDealRiskSourceStates);
            }
        }

        Set<ReminderKey> historicallySuppressed = reconcileHistoricalBaselines(
            workspaceId,
            expected,
            managedTypes,
            baselines,
            contactSourceStateHashes,
            preferences,
            dealRiskSourceStates);
        expected.entrySet().stream()
            .filter(entry -> !historicallySuppressed.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .forEach(notificationDelivery::deliver);
        for (Map.Entry<ReminderKey, Notification> entry : existing.entrySet()) {
            Notification notification = entry.getValue();
            if (notification.getResolvedAt() == null
                    && managedTypes.contains(notification.getType())
                    && !expected.containsKey(entry.getKey())
                    && !historicallySuppressed.contains(entry.getKey())) {
                int rows = notificationMapper.resolveReminder(
                    workspaceId,
                    notification.getRecipientId(),
                    notification.getId(),
                    triggeredAt
                );
                if (rows > 0) {
                    stateVersionService.markChanged(notification.getRecipientId());
                }
            }
        }
    }

    HistoricalExpectationSnapshot historicalExpectationSnapshot(
            int workspaceId,
            Instant evaluationInstant) {
        return historicalExpectationSnapshot(
            workspaceId,
            evaluationInstant,
            HistoricalBaselineScope.empty());
    }

    HistoricalExpectationSnapshot historicalExpectationSnapshot(
            int workspaceId,
            Instant evaluationInstant,
            HistoricalBaselineScope exclusion) {
        String triggeredAt = utcTimestamp(evaluationInstant);
        Map<ReminderKey, Notification> existing = loadExisting(workspaceId);
        Map<PreferenceKey, Boolean> preferences = Map.of();
        Map<ReminderKey, Notification> expected = new LinkedHashMap<>();
        addTaskReminders(
            workspaceId,
            existing,
            expected,
            preferences,
            triggeredAt,
            evaluationInstant,
            exclusion.taskIds());
        addDealCloseReminders(
            workspaceId,
            existing,
            expected,
            preferences,
            triggeredAt,
            evaluationInstant);
        RelationshipSourceSnapshot relationshipSources =
            relationshipSourceSnapshotStrict(
                workspaceId, evaluationInstant, exclusion);
        Map<Integer, RelationshipTemperatureDto> temperatures =
            relationshipSources.temperatures();
        Map<Integer, String> contactSourceStateHashes =
            relationshipSources.sourceStateHashes();
        addRelationshipNudges(
            workspaceId,
            existing,
            expected,
            preferences,
            triggeredAt,
            temperatures,
            evaluationInstant);
        if (properties.isIntroOpportunitiesEnabled()) {
            addIntroOpportunities(
                workspaceId, expected, preferences, triggeredAt, temperatures);
        }
        if (properties.isDealRiskEnabled()) {
            Map<ReminderKey, String> dealRiskSourceStates = new LinkedHashMap<>();
            addDealRiskNotifications(
                workspaceId,
                expected,
                preferences,
                triggeredAt,
                temperatures,
                contactSourceStateHashes,
                dealRiskSourceStates);
            Map<HistoricalExpectationKey, String> historicalSourceStates =
                historicalSourceStates(workspaceId, dealRiskSourceStates);
            Map<HistoricalExpectationKey, HistoricalExpectation> snapshot =
                historicalExpectations(
                    workspaceId,
                    expected,
                    contactSourceStateHashes,
                    dealRiskSourceStates);
            return new HistoricalExpectationSnapshot(snapshot, historicalSourceStates);
        }
        return new HistoricalExpectationSnapshot(
            historicalExpectations(
                workspaceId,
                expected,
                contactSourceStateHashes,
                Map.of()),
            Map.of());
    }

    private Map<HistoricalExpectationKey, HistoricalExpectation> historicalExpectations(
            int workspaceId,
            Map<ReminderKey, Notification> expected,
            Map<Integer, String> contactSourceStateHashes,
            Map<ReminderKey, String> sourceStates) {
        Map<HistoricalExpectationKey, HistoricalExpectation> snapshot = new LinkedHashMap<>();
        for (Map.Entry<ReminderKey, Notification> entry : expected.entrySet()) {
            ReminderKey key = entry.getKey();
            Notification notification = entry.getValue();
            HistoricalExpectationKey snapshotKey = new HistoricalExpectationKey(
                key.workspaceId(), key.recipientId(), key.dedupeKey());
            snapshot.put(
                snapshotKey,
                historicalExpectation(
                    notification,
                    contactSourceStateHashes,
                    sourceStates.get(key)));
        }
        return snapshot;
    }

    private static Map<HistoricalExpectationKey, String> historicalSourceStates(
            int workspaceId,
            Map<ReminderKey, String> sourceStates) {
        Map<HistoricalExpectationKey, String> historical = new LinkedHashMap<>();
        sourceStates.forEach((key, sourceStateHash) -> historical.put(
            new HistoricalExpectationKey(
                workspaceId,
                key.recipientId(),
                key.dedupeKey()),
            sourceStateHash));
        return historical;
    }

    void persistHistoricalBaselines(
            int workspaceId,
            HistoricalExpectationSnapshot before,
            HistoricalExpectationSnapshot after,
            HistoricalBaselineScope scope,
            String importRunId) {
        List<HistoricalNotificationBaseline> baselines = new ArrayList<>();
        Map<HistoricalExpectationKey, HistoricalNotificationBaseline> existingBaselines =
            new HashMap<>();
        for (HistoricalNotificationBaseline baseline
                : notificationMapper.findHistoricalNotificationBaselines(workspaceId)) {
            existingBaselines.put(
                new HistoricalExpectationKey(
                    workspaceId,
                    baseline.getRecipientId(),
                    baseline.getDedupeKey()),
                baseline);
        }
        for (Map.Entry<HistoricalExpectationKey, HistoricalExpectation> entry
                : after.expectations().entrySet()) {
            HistoricalExpectation previous = before.expectations().get(entry.getKey());
            HistoricalExpectation current = entry.getValue();
            if (current.equals(previous)
                    || !scope.includes(entry.getKey(), current)) {
                continue;
            }
            HistoricalNotificationBaseline existingBaseline =
                existingBaselines.get(entry.getKey());
            if (existingBaseline != null
                    && (previous == null
                        || !existingBaseline.getNotificationType().equals(previous.type())
                        || !existingBaseline.getSourceStateHash().equals(
                            previous.sourceStateHash()))) {
                continue;
            }
            HistoricalNotificationBaseline baseline =
                new HistoricalNotificationBaseline();
            baseline.setWorkspaceId(workspaceId);
            baseline.setRecipientId(entry.getKey().recipientId());
            baseline.setDedupeKey(entry.getKey().dedupeKey());
            baseline.setNotificationType(current.type());
            baseline.setBaselineSeverity(current.severity());
            baseline.setSourceStateHash(current.sourceStateHash());
            baseline.setImportRunId(importRunId);
            baselines.add(baseline);
        }
        for (Map.Entry<HistoricalExpectationKey, HistoricalExpectation> entry
                : before.expectations().entrySet()) {
            HistoricalExpectation previous = entry.getValue();
            if (after.expectations().containsKey(entry.getKey())
                    || !DEAL_RISK_TYPE.equals(previous.type())
                    || !scope.includes(entry.getKey(), previous)) {
                continue;
            }
            String sourceStateHash =
                after.sourceStateHashes().get(entry.getKey());
            if (sourceStateHash == null) {
                continue;
            }
            HistoricalNotificationBaseline existingBaseline =
                existingBaselines.get(entry.getKey());
            if (existingBaseline != null
                    && (!existingBaseline.getNotificationType().equals(previous.type())
                        || !existingBaseline.getSourceStateHash().equals(
                            previous.sourceStateHash()))) {
                continue;
            }
            HistoricalNotificationBaseline baseline =
                new HistoricalNotificationBaseline();
            baseline.setWorkspaceId(workspaceId);
            baseline.setRecipientId(entry.getKey().recipientId());
            baseline.setDedupeKey(entry.getKey().dedupeKey());
            baseline.setNotificationType(previous.type());
            baseline.setBaselineSeverity(previous.severity());
            baseline.setSourceStateHash(sourceStateHash);
            baseline.setImportRunId(importRunId);
            baselines.add(baseline);
        }
        for (int offset = 0; offset < baselines.size(); offset += BASELINE_BATCH_SIZE) {
            notificationMapper.insertHistoricalNotificationBaselines(
                workspaceId,
                baselines.subList(
                    offset,
                    Math.min(offset + BASELINE_BATCH_SIZE, baselines.size())));
        }
    }

    private Set<ReminderKey> reconcileHistoricalBaselines(
            int workspaceId,
            Map<ReminderKey, Notification> expected,
            Set<String> managedTypes,
            List<HistoricalNotificationBaseline> baselines,
            Map<Integer, String> contactSourceStateHashes,
            Map<PreferenceKey, Boolean> preferences,
            Map<ReminderKey, String> dealRiskSourceStates) {
        Set<ReminderKey> suppressed = new HashSet<>();
        List<HistoricalNotificationBaseline> removals = new ArrayList<>();
        for (HistoricalNotificationBaseline baseline : baselines) {
            ReminderKey key = new ReminderKey(
                workspaceId, baseline.getRecipientId(), baseline.getDedupeKey());
            Notification notification = expected.get(key);
            HistoricalExpectation current = notification == null
                ? null
                : historicalExpectation(
                    notification,
                    contactSourceStateHashes,
                    dealRiskSourceStates.get(key));
            if (notification == null
                    && !enabled(
                        preferences,
                        baseline.getRecipientId(),
                        baseline.getNotificationType())) {
                continue;
            }
            if (notification != null
                    && baseline.getNotificationType().equals(current.type())
                    && baseline.getSourceStateHash().equals(current.sourceStateHash())) {
                suppressed.add(key);
            } else if (notification == null
                    && DEAL_RISK_TYPE.equals(baseline.getNotificationType())
                    && baseline.getSourceStateHash().equals(
                        dealRiskSourceStates.get(key))) {
                suppressed.add(key);
            } else if (notification != null
                    || managedTypes.contains(baseline.getNotificationType())) {
                removals.add(baseline);
            }
        }
        for (int offset = 0; offset < removals.size(); offset += BASELINE_BATCH_SIZE) {
            List<HistoricalNotificationBaseline> batch = removals.subList(
                offset,
                Math.min(offset + BASELINE_BATCH_SIZE, removals.size()));
            int deleted = notificationMapper.deleteHistoricalNotificationBaselines(
                workspaceId, batch);
            if (deleted != batch.size()) {
                for (HistoricalNotificationBaseline baseline : batch) {
                    ReminderKey key = new ReminderKey(
                        workspaceId,
                        baseline.getRecipientId(),
                        baseline.getDedupeKey());
                    if (expected.containsKey(key)) {
                        suppressed.add(key);
                    }
                }
            }
        }
        return suppressed;
    }

    /**
     * Runs one reminder pass atomically and records whether its type may be resolved this cycle.
     * The pass stages its notifications into a private map that is merged into {@code expected}
     * only on success, so a mid-pass failure never delivers a partial subset. A disabled pass
     * ({@code enabled} false) contributes nothing but still counts as managed, so its stale
     * reminders are cleaned up; a pass whose prerequisite is unavailable ({@code ready} false —
     * the shared warmth rescore failed) is skipped and left unmanaged; a pass that throws is
     * logged, its staged notifications are discarded, and its type is left unmanaged so the
     * resolve pass cannot clear reminders that were never recomputed.
     */
    private void runManagedPass(
        int workspaceId,
        Map<ReminderKey, Notification> expected,
        Set<String> managedTypes,
        String type,
        boolean enabled,
        boolean ready,
        String label,
        Consumer<Map<ReminderKey, Notification>> pass
    ) {
        if (!enabled) {
            managedTypes.add(type);
            return;
        }
        if (!ready) {
            return;
        }
        try {
            Map<ReminderKey, Notification> staged = new LinkedHashMap<>();
            pass.accept(staged);
            expected.putAll(staged);
            managedTypes.add(type);
        } catch (RuntimeException exception) {
            log.warn("{} reconciliation failed for workspace={}", label, workspaceId, exception);
        }
    }

    private void addTaskReminders(
        int workspaceId,
        Map<ReminderKey, Notification> existing,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Instant evaluationInstant
    ) {
        addTaskReminders(
            workspaceId,
            existing,
            expected,
            preferences,
            triggeredAt,
            evaluationInstant,
            Set.of());
    }

    private void addTaskReminders(
        int workspaceId,
        Map<ReminderKey, Notification> existing,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Instant evaluationInstant,
        Set<Integer> excludedTaskIds
    ) {
        for (TaskReminderCandidate candidate : notificationMapper.findTaskReminderCandidates(workspaceId)) {
            if (excludedTaskIds.contains(candidate.getTaskId())) {
                continue;
            }
            String dedupeKey = "task.due:" + candidate.getTaskId();
            ReminderKey key = new ReminderKey(workspaceId, candidate.getRecipientId(), dedupeKey);
            LocalDate today = LocalDate.ofInstant(
                evaluationInstant, zone(candidate.getRecipientTimezone()));
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
    }

    private void addDealCloseReminders(
        int workspaceId,
        Map<ReminderKey, Notification> existing,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Instant evaluationInstant
    ) {
        for (DealReminderCandidate candidate : notificationMapper.findDealReminderCandidates(workspaceId)) {
            String dedupeKey = "deal.close:" + candidate.getDealId();
            ReminderKey key = new ReminderKey(workspaceId, candidate.getRecipientId(), dedupeKey);
            LocalDate today = LocalDate.ofInstant(
                evaluationInstant, zone(candidate.getRecipientTimezone()));
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
    }

    private void addRelationshipNudges(
        int workspaceId,
        Map<ReminderKey, Notification> existing,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Map<Integer, RelationshipTemperatureDto> temperatures,
        Instant evaluationInstant
    ) {
        List<RelationshipNudgeCandidate> nudgeCandidates =
            notificationMapper.findRelationshipNudgeCandidates(workspaceId);
        if (nudgeCandidates.isEmpty()) {
            return;
        }
        double highValueThreshold = highValueThreshold(notificationMapper.findOpenDealValues(workspaceId));
        LocalDate today = LocalDate.ofInstant(evaluationInstant, ZoneOffset.UTC);
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
            expected.put(key, relationshipNudgeNotification(
                candidate, temperature, severity, reasons, dedupeKey, triggeredAt));
        }
    }

    /**
     * Surfaces open deals the risk engine flags as high or medium (see {@link DealRiskService}) to
     * each deal's owner and collaborators. Low-level risk is intentionally not pushed — it stays on
     * the deal page, not the inbox. Deduped per (deal, recipient); the resolve pass clears a
     * notification once the deal is no longer at risk or has closed. A same-severity re-emit does not
     * disturb a dismissed notification, but any severity change (in either direction) re-surfaces it
     * as unread — the shared upsert's behaviour for every reminder type. Assesses every open deal in
     * the workspace against the sweep's shared warmth map, so it runs only on the scheduled sweep.
     */
    private void addDealRiskNotifications(
        int workspaceId,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Map<Integer, RelationshipTemperatureDto> temperatures,
        Map<Integer, String> contactSourceStateHashes,
        Map<ReminderKey, String> sourceStates
    ) {
        Map<Integer, DealRiskService.NotificationRiskState> byDeal = new HashMap<>();
        for (DealRiskService.NotificationRiskState state
                : dealRiskService.assessWorkspaceNotificationStates(
                    workspaceId,
                    temperatures,
                    contactSourceStateHashes)) {
            byDeal.put(state.assessment().getDealId(), state);
        }
        if (byDeal.isEmpty()) {
            return;
        }
        for (OpenDealRecipient recipient : notificationMapper.findOpenDealRecipients(workspaceId)) {
            DealRiskService.NotificationRiskState state =
                byDeal.get(recipient.getDealId());
            if (state == null) {
                continue;
            }
            String dedupeKey = DEAL_RISK_TYPE + ":" + recipient.getDealId();
            ReminderKey key = new ReminderKey(
                workspaceId, recipient.getRecipientId(), dedupeKey);
            sourceStates.put(key, state.sourceStateHash());
            DealRiskDto risk = state.assessment();
            String severity = riskSeverity(risk.getLevel());
            if (severity == null || !enabled(preferences, recipient.getRecipientId(), DEAL_RISK_TYPE)) {
                continue;
            }
            expected.put(key, dealRiskNotification(workspaceId, recipient, risk, severity, dedupeKey, triggeredAt));
        }
    }

    /** Notification severity for a deal-risk level: high → critical, medium → warning; low/none not pushed. */
    private static String riskSeverity(String level) {
        if (RISK_HIGH.equals(level)) {
            return CRITICAL;
        }
        if (RISK_MEDIUM.equals(level)) {
            return WARNING;
        }
        return null;
    }

    private Notification dealRiskNotification(
        int workspaceId,
        OpenDealRecipient recipient,
        DealRiskDto risk,
        String severity,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = base(
            workspaceId,
            recipient.getRecipientId(),
            DEAL_RISK_TYPE,
            "deal",
            severity,
            "deal",
            recipient.getDealId(),
            recipient.getDealLabel(),
            dedupeKey,
            triggeredAt
        );
        notification.setContextType("deal");
        notification.setContextId(recipient.getDealId());
        notification.setContextLabel(recipient.getDealLabel());
        notification.setTitle(CRITICAL.equals(severity) ? "Deal at high risk" : "Deal needs attention");
        notification.setBody(recipient.getDealLabel() + " — " + risk.getLevel() + " risk");
        notification.setActionUrl("/records/deals/" + recipient.getDealId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dealId", recipient.getDealId());
        data.put("deal", recipient.getDealLabel());
        data.put("level", risk.getLevel());
        data.put("score", risk.getScore());
        data.put("factorCount", risk.getFactors().size());
        if (!risk.getFactors().isEmpty()) {
            data.put("topFactor", risk.getFactors().get(0).getCode());
        }
        notification.setData(json(data));
        return notification;
    }

    /**
     * Surfaces the workspace's top reverse-introduction opportunities (issue #43) as info-level
     * nudges to every member — anyone can make the intro. Each opportunity is deduped per recipient
     * by pair; when a pair is introduced, dismissed, or otherwise drops out of the top suggestions,
     * the resolve pass clears its notifications. Like the relationship-nudge pass, this rescores the
     * whole workspace, so it runs only on the scheduled sweep.
     */
    private void addIntroOpportunities(
        int workspaceId,
        Map<ReminderKey, Notification> expected,
        Map<PreferenceKey, Boolean> preferences,
        String triggeredAt,
        Map<Integer, RelationshipTemperatureDto> temperatures
    ) {
        List<IntroSuggestionDto> suggestions = introductionService.computeSuggestions(
            workspaceId, properties.getIntroOpportunityLimit(), temperatures);
        if (suggestions.isEmpty()) {
            return;
        }
        List<Integer> recipients = notificationMapper.findWorkspaceRecipientIds(workspaceId);
        for (IntroSuggestionDto suggestion : suggestions) {
            String dedupeKey = INTRO_OPPORTUNITY_TYPE + ":"
                + suggestion.getPersonAId() + ":" + suggestion.getPersonBId();
            for (Integer recipientId : recipients) {
                if (!enabled(preferences, recipientId, INTRO_OPPORTUNITY_TYPE)) {
                    continue;
                }
                ReminderKey key = new ReminderKey(workspaceId, recipientId, dedupeKey);
                expected.put(key, introOpportunityNotification(
                    workspaceId, recipientId, suggestion, dedupeKey, triggeredAt));
            }
        }
    }

    private Notification introOpportunityNotification(
        int workspaceId,
        int recipientId,
        IntroSuggestionDto suggestion,
        String dedupeKey,
        String triggeredAt
    ) {
        Notification notification = base(
            workspaceId,
            recipientId,
            INTRO_OPPORTUNITY_TYPE,
            "relationship",
            INFO,
            "person",
            suggestion.getPersonAId(),
            suggestion.getPersonAName(),
            dedupeKey,
            triggeredAt
        );
        notification.setContextType("person");
        notification.setContextId(suggestion.getPersonBId());
        notification.setContextLabel(suggestion.getPersonBName());
        notification.setTitle("Introduction opportunity");
        notification.setBody("You could introduce " + suggestion.getPersonAName()
            + " to " + suggestion.getPersonBName());
        notification.setActionUrl("/overview/introductions");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("personAId", suggestion.getPersonAId());
        data.put("personAName", suggestion.getPersonAName());
        data.put("personBId", suggestion.getPersonBId());
        data.put("personBName", suggestion.getPersonBName());
        data.put("score", suggestion.getScore());
        data.put("mutualConnections", suggestion.getMutualConnections());
        if (suggestion.getSharedCompany() != null) {
            data.put("sharedCompany", suggestion.getSharedCompany());
        }
        if (suggestion.getReasons() != null && !suggestion.getReasons().isEmpty()) {
            data.put("reasons", suggestion.getReasons());
        }
        notification.setData(json(data));
        return notification;
    }

    /**
     * Reasons a decaying relationship's nudge is high-priority: a soon-closing, high-value, or
     * late-stage deal, or a named key stakeholder. Carried in the notification payload so the inbox
     * can surface priority separately from severity, which stays the (stable) decay state — keeping
     * a volatile, workspace-relative signal out of the severity that gates read/dismiss resets.
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
        List<Integer> recipientIds = notificationMapper.findPurgeRecipientIds(workspaceId, cutoff);
        int rows = notificationMapper.purgeWorkspaceReminderHistory(workspaceId, cutoff);
        if (rows > 0) {
            recipientIds.forEach(stateVersionService::markChanged);
        }
        return rows;
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
     * with long-dormant relationships does not flood inboxes on the first sweep.
     *
     * <p>Resolution is monotonic: once a nudge exists ({@code reminderExists}) it is kept at the
     * current decay severity while the contact stays {@code cool} or {@code cold} — even if the
     * windowed {@code cooling} trend has fluttered back to steady with no new touch. Only a genuine
     * warm-up (the band rising to {@code warm}/{@code hot}, which needs fresh activity) or a fresh
     * touch ({@code daysSinceTouch} dropping below the minimum) resolves it, so a nudge the user
     * already cleared cannot re-surface as the trend oscillates.
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
        if (reminderExists) {
            return COOL_BAND.equals(band) ? WARNING : null;
        }
        return COOLING_TREND.equals(trend) ? WARNING : null;
    }

    /**
     * Warmth for every contact in the workspace, keyed by person id — or {@code null} when scoring
     * fails, in which case the caller skips the temperature-dependent passes for this cycle (their
     * types stay unmanaged, preserving existing notifications) while task and deal reminders still
     * deliver. Decay is time-driven, so the next sweep recovers naturally once scoring succeeds.
     */
    private RelationshipSourceSnapshot relationshipSourceSnapshot(
            int workspaceId,
            Instant evaluationInstant) {
        try {
            Map<Integer, RelationshipTemperatureDto> temperatures = new HashMap<>();
            for (RelationshipTemperatureDto temperature
                    : scoringService.scoreContacts(
                        workspaceId, evaluationInstant)) {
                temperatures.put(temperature.getId(), temperature);
            }
            return new RelationshipSourceSnapshot(
                temperatures,
                scoringService.contactSourceStateHashes(
                    workspaceId,
                    Set.of(),
                    Set.of(),
                    Set.of()));
        } catch (RuntimeException exception) {
            log.warn("Warmth scoring failed for workspace={}; skipping relationship passes this cycle",
                workspaceId, exception);
            return null;
        }
    }

    private RelationshipSourceSnapshot relationshipSourceSnapshotStrict(
            int workspaceId,
            Instant evaluationInstant,
            HistoricalBaselineScope exclusion) {
        Map<Integer, RelationshipTemperatureDto> temperatures = new HashMap<>();
        for (RelationshipTemperatureDto temperature
                : scoringService.scoreContactsExcludingHistoryImports(
                    workspaceId,
                    evaluationInstant,
                    exclusion.activityIds(),
                    exclusion.noteIds(),
                    exclusion.taskIds())) {
            temperatures.put(temperature.getId(), temperature);
        }
        Map<Integer, String> sourceStateHashes =
            scoringService.contactSourceStateHashes(
                workspaceId,
                exclusion.activityIds(),
                exclusion.noteIds(),
                exclusion.taskIds());
        return new RelationshipSourceSnapshot(
            temperatures,
            sourceStateHashes);
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

    private HistoricalExpectation historicalExpectation(
            Notification notification,
            Map<Integer, String> contactSourceStateHashes,
            String sourceStateHashOverride) {
        return new HistoricalExpectation(
            notification.getType(),
            notification.getSeverity(),
            sourceStateHashOverride == null
                ? sourceStateHash(notification, contactSourceStateHashes)
                : sourceStateHashOverride);
    }

    private String sourceStateHash(
            Notification notification,
            Map<Integer, String> contactSourceStateHashes) {
        List<String> values = new ArrayList<>();
        values.add(notification.getType());
        values.add(notification.getActorId() == null
            ? null
            : notification.getActorId().toString());
        values.add(notification.getSourceType());
        values.add(notification.getSourceId() == null
            ? null
            : notification.getSourceId().toString());
        values.add(notification.getContextType());
        values.add(notification.getContextId() == null
            ? null
            : notification.getContextId().toString());
        switch (notification.getType()) {
            case TASK_TYPE -> {
                values.add(notification.getBody());
                values.add(notification.getData());
            }
            case RELATIONSHIP_TYPE -> {
                addContactSourceState(
                    values,
                    contactSourceStateHashes,
                    notification.getSourceId());
                addJsonState(
                    values,
                    notification.getData(),
                    "dealValue",
                    "expectedCloseDate",
                    "role");
            }
            case DEAL_RISK_TYPE -> values.add(
                notification.getSourceId() == null
                    ? null
                    : notification.getSourceId().toString());
            case INTRO_OPPORTUNITY_TYPE -> {
                addContactSourceState(
                    values,
                    contactSourceStateHashes,
                    notification.getSourceId());
                addContactSourceState(
                    values,
                    contactSourceStateHashes,
                    notification.getContextId());
                addJsonState(
                    values,
                    notification.getData(),
                    "mutualConnections",
                    "sharedCompany",
                    "reasons");
            }
            default -> {
                values.add(notification.getBody());
                values.add(notification.getData());
            }
        }
        return hashValues(values);
    }

    private static void addContactSourceState(
            List<String> values,
            Map<Integer, String> contactSourceStateHashes,
            Integer personId) {
        values.add(
            personId == null
                ? ScoringService.emptyContactSourceStateHash()
                : contactSourceStateHashes.getOrDefault(
                    personId,
                    ScoringService.emptyContactSourceStateHash()));
    }

    private void addJsonState(
            List<String> values,
            String data,
            String... fields) {
        try {
            JsonNode root = data == null ? null : objectMapper.readTree(data);
            for (String field : fields) {
                JsonNode value = root == null ? null : root.get(field);
                values.add(value == null ? null : value.toString());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                "Could not read notification expectation data",
                exception);
        }
    }

    private static String hashValues(List<String> values) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        for (String value : values) {
            if (value == null) {
                digest.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                continue;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(
                ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ZoneId zone(String timezone) {
        return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
    }

    private static String utcTimestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(MYSQL_DATETIME);
    }

    private record ReminderKey(int workspaceId, int recipientId, String dedupeKey) {}

    private record PreferenceKey(int recipientId, String type) {}

    private record RelationshipSourceSnapshot(
        Map<Integer, RelationshipTemperatureDto> temperatures,
        Map<Integer, String> sourceStateHashes
    ) {}

    record HistoricalExpectationKey(int workspaceId, int recipientId, String dedupeKey) {}

    record HistoricalExpectation(
            String type,
            String severity,
            String sourceStateHash) {

        HistoricalExpectation(String type, String severity) {
            this(type, severity, hashValues(List.of(type, severity)));
        }
    }

    record HistoricalExpectationSnapshot(
            Map<HistoricalExpectationKey, HistoricalExpectation> expectations,
            Map<HistoricalExpectationKey, String> sourceStateHashes) {

        HistoricalExpectationSnapshot {
            expectations = Map.copyOf(expectations);
            sourceStateHashes = Map.copyOf(sourceStateHashes);
        }

        HistoricalExpectationSnapshot(
                Map<HistoricalExpectationKey, HistoricalExpectation> expectations) {
            this(expectations, Map.of());
        }
    }

    record HistoricalBaselineScope(
            Set<Integer> personIds,
            Set<Integer> activityIds,
            Set<Integer> noteIds,
            Set<Integer> taskIds) {

        HistoricalBaselineScope {
            personIds = Set.copyOf(personIds);
            activityIds = Set.copyOf(activityIds);
            noteIds = Set.copyOf(noteIds);
            taskIds = Set.copyOf(taskIds);
        }

        static HistoricalBaselineScope empty() {
            return new HistoricalBaselineScope(
                Set.of(), Set.of(), Set.of(), Set.of());
        }

        boolean includes(
                HistoricalExpectationKey key,
                HistoricalExpectation expectation) {
            return switch (expectation.type()) {
                case TASK_TYPE ->
                    containsKeyId(taskIds, key.dedupeKey(), TASK_TYPE, 0);
                case RELATIONSHIP_TYPE ->
                    containsKeyId(personIds, key.dedupeKey(), RELATIONSHIP_TYPE, 1);
                case INTRO_OPPORTUNITY_TYPE ->
                    containsKeyId(personIds, key.dedupeKey(), INTRO_OPPORTUNITY_TYPE, 0)
                        || containsKeyId(
                            personIds, key.dedupeKey(), INTRO_OPPORTUNITY_TYPE, 1);
                case DEAL_RISK_TYPE -> !personIds.isEmpty();
                default -> false;
            };
        }

        boolean sameRelevantExpectations(
                HistoricalExpectationSnapshot before,
                HistoricalExpectationSnapshot counterfactual) {
            return relevantExpectations(before).equals(
                relevantExpectations(counterfactual));
        }

        private Map<HistoricalExpectationKey, HistoricalExpectation> relevantExpectations(
                HistoricalExpectationSnapshot snapshot) {
            Map<HistoricalExpectationKey, HistoricalExpectation> relevant =
                new LinkedHashMap<>();
            snapshot.expectations().forEach((key, expectation) -> {
                if (includes(key, expectation)) {
                    relevant.put(key, expectation);
                }
            });
            return relevant;
        }
    }

    private static boolean containsKeyId(
            Set<Integer> allowedIds,
            String dedupeKey,
            String prefix,
            int index) {
        String expectedPrefix = prefix + ":";
        if (!dedupeKey.startsWith(expectedPrefix)) {
            return false;
        }
        String[] parts = dedupeKey.substring(expectedPrefix.length()).split(":");
        if (index >= parts.length) {
            return false;
        }
        try {
            return allowedIds.contains(Integer.parseInt(parts[index]));
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
