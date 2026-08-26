package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import ooo.klae.connex.backend.beans.AiBriefSchedule;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatQueryScopeRequest;
import ooo.klae.connex.backend.dto.AiChatSessionCreateRequest;
import ooo.klae.connex.backend.dto.AiChatSessionDto;
import ooo.klae.connex.backend.dto.AiChatTurnAcceptedDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiBriefScheduleMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.AiAssistantService;
import ooo.klae.connex.backend.services.UserService;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts and delivers one member's scheduled brief through the ordinary Ask Connex turn machinery.
 *
 * <p>A scheduled brief is an ordinary session and an ordinary turn under the member's own identity.
 * That is the point: oversight, retention, budgets, restriction epochs, masking, and audit are the
 * ones that already govern an interactive turn, and no second AI path exists that could drift from
 * them. Permissions are re-resolved at run time from current membership, never from anything stored
 * on the schedule.
 *
 * <p>Running and delivering are two passes. The claim is taken before generation, so a run happens at
 * most once per member per local period across every instance; the notification is written only once
 * the durable turn has actually resolved, so a failed or timed-out brief is dropped silently instead
 * of announcing itself. Neither pass ever retries within the period, which is what stops a broken
 * provider from turning into a stream of notifications.
 */
@Service
@RequiredArgsConstructor
public class AiBriefRunService {
    private static final Logger log = LoggerFactory.getLogger(AiBriefRunService.class);
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter STORED_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]");

    /** Period keys, matching the durable {@code pending_kind} vocabulary. */
    static final String DAILY = "daily";
    static final String WEEKLY = "weekly";

    /** Stable reasons a scheduled brief produced no notification. */
    static final String REASON_GENERATION_FAILED = "generation_failed";
    static final String REASON_STALLED = "generation_stalled";
    static final String REASON_START_FAILED = "start_failed";
    static final String REASON_ACCESS_LOST = "access_lost";

    static final String NOTIFICATION_TYPE = "ai.brief.ready";
    private static final String NOTIFICATION_CATEGORY = "assistant";
    private static final String NOTIFICATION_SEVERITY = "info";

    /**
     * How long an in-flight brief may stay non-terminal before the sweep stops waiting for it.
     *
     * <p>Comfortably longer than the assistant turn budget: the only purpose is to release a pending
     * row whose turn will never reach a terminal state, so it must never fire on a turn that is
     * merely slow.
     */
    private static final Duration STALL_CUTOFF = Duration.ofHours(2);

    private final AiBriefScheduleMapper scheduleMapper;
    private final AiFeatureGate featureGate;
    private final AiGenerationContextRunner contextRunner;
    private final AiAssistantService assistantService;
    private final AiAssistantTurnService turnService;
    private final AiChatTurnPersistenceService persistenceService;
    private final NotificationDelivery notificationDelivery;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    /** The outcome of one scheduled brief pass, recorded for observability. */
    public enum Outcome { STARTED, DELIVERED, SKIPPED, FAILED }

    /**
     * Claims one due period and starts its brief turn.
     *
     * <p>Readiness is checked before the claim rather than after. A workspace whose organization has
     * no usable provider must not burn the day's claim on a run it cannot perform: leaving the claim
     * unspent means the brief simply starts working the moment AI becomes available, and no error is
     * ever delivered in the meantime.
     *
     * @param schedule the member's schedule, already read in the workspace's catalog
     * @param kind {@link #DAILY} or {@link #WEEKLY}
     * @param claimOn the member's local date the run is claimed for
     * @return what the pass did
     */
    public Outcome start(AiBriefSchedule schedule, String kind, LocalDate claimOn) {
        User owner = userService.getActiveWorkspaceUser(
                schedule.getWorkspaceId(), schedule.getUserId());
        Locale locale = locale(owner);
        Outcome[] outcome = { Outcome.SKIPPED };
        contextRunner.run(
                schedule.getWorkspaceId(), schedule.getUserId(), locale,
                () -> outcome[0] = startAsOwner(schedule, kind, claimOn, locale));
        return outcome[0];
    }

    /**
     * Claims the period and starts the turn under the owner's installed identity and language.
     *
     * <p>A failure to start spends the claim rather than releasing it. A brief that could not start
     * is not retried inside its own period and is never announced, because a member who asked for one
     * useful summary a day is not asking to be told repeatedly that it failed.
     *
     * @param locale the owner's own language, which the durable session title and request are written
     *     in so a scheduled run does not leave English, apparently member-authored text in a
     *     Japanese workspace
     */
    private Outcome startAsOwner(
            AiBriefSchedule schedule, String kind, LocalDate claimOn, Locale locale) {
        if (!featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)) {
            return Outcome.SKIPPED;
        }
        if (scheduleMapper.claimPeriod(
                schedule.getWorkspaceId(), schedule.getId(), kind, claimOn.toString()) != 1) {
            return Outcome.SKIPPED;
        }
        try {
            AiChatSessionDto session = assistantService.create(
                    sessionRequest(kind, claimOn, locale));
            AiChatTurnAcceptedDto accepted = turnService.start(
                    session.getId(), turnRequest(kind, locale));
            scheduleMapper.attachPendingTurn(
                    schedule.getWorkspaceId(), schedule.getId(), kind,
                    session.getId(), accepted.turnId(), nowUtc());
            return Outcome.STARTED;
        } catch (RuntimeException exception) {
            scheduleMapper.recordStartFailure(
                    schedule.getWorkspaceId(), schedule.getId(),
                    REASON_START_FAILED, nowUtc());
            log.warn("Scheduled brief could not start workspace={} kind={} exceptionClass={}",
                    schedule.getWorkspaceId(), kind, exception.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /**
     * Delivers or drops one member's in-flight brief.
     *
     * <p>The feature gate is re-evaluated here, not only before the claim, because generation and
     * delivery are separated in time and the gate is the workspace's live answer rather than a fact
     * captured at start.
     *
     * <p>Only a definitive answer about access drops the brief. A member who is no longer a member
     * has genuinely lost it, and dropping is right; a database timeout or a routing failure has said
     * nothing about access at all, and treating it the same way would discard a generated brief that
     * a later sweep could still have delivered. Transient failures therefore propagate and leave the
     * pending row exactly as it was.
     *
     * @param schedule the schedule carrying a pending turn
     * @return what the pass did
     */
    public Outcome deliverPending(AiBriefSchedule schedule) {
        if (schedule.getPendingTurnId() == null || schedule.getPendingSessionId() == null) {
            return Outcome.SKIPPED;
        }
        User owner;
        try {
            owner = userService.getActiveWorkspaceUser(
                    schedule.getWorkspaceId(), schedule.getUserId());
        } catch (ResourceNotFoundException | ForbiddenException exception) {
            release(schedule, false, REASON_ACCESS_LOST);
            return Outcome.SKIPPED;
        }
        Outcome[] outcome = { Outcome.SKIPPED };
        contextRunner.run(
                schedule.getWorkspaceId(), schedule.getUserId(), locale(owner),
                () -> outcome[0] = deliverAsOwner(schedule, owner));
        return outcome[0];
    }

    /**
     * Decides one in-flight brief's fate with the owner's identity already installed.
     *
     * <p>The gate can close between the start pass and this one — the kill switch flips, the
     * organization disables AI, the member loses {@code AI_USE}. Announcing a brief generated under
     * the old fact would deliver assistant output into a workspace that has since said no, so the
     * pending row is dropped rather than released as delivered. Losing read access to one's own brief
     * is the same kind of answer and is treated the same way; anything that is merely a failure to
     * read propagates instead, leaving the row for the next sweep.
     */
    private Outcome deliverAsOwner(AiBriefSchedule schedule, User owner) {
        if (!featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)) {
            release(schedule, false, REASON_ACCESS_LOST);
            return Outcome.SKIPPED;
        }
        AiChatTurn turn;
        try {
            turn = persistenceService.readTurn(
                    schedule.getPendingSessionId(), schedule.getPendingTurnId());
        } catch (ResourceNotFoundException | ForbiddenException exception) {
            release(schedule, false, REASON_ACCESS_LOST);
            return Outcome.SKIPPED;
        }
        String status = turn == null ? null : turn.getStatus();
        if ("resolved".equals(status)) {
            return releaseAndNotify(schedule, owner);
        }
        if ("failed".equals(status) || "timed_out".equals(status)) {
            release(schedule, false, REASON_GENERATION_FAILED);
            return Outcome.FAILED;
        }
        if (stalled(schedule)) {
            release(schedule, false, REASON_STALLED);
            return Outcome.FAILED;
        }
        return Outcome.SKIPPED;
    }

    /**
     * Whether an in-flight brief has been pending long enough to stop waiting for it.
     *
     * <p>An unparseable stored timestamp is treated as not stalled: releasing a row because its
     * timestamp could not be read would drop a brief that may still be about to resolve.
     */
    private boolean stalled(AiBriefSchedule schedule) {
        String startedAt = schedule.getPendingStartedAt();
        if (startedAt == null || startedAt.isBlank()) {
            return false;
        }
        try {
            LocalDateTime started = LocalDateTime.parse(startedAt.trim(), STORED_TIMESTAMP);
            return started.plus(STALL_CUTOFF)
                    .isBefore(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean release(AiBriefSchedule schedule, boolean delivered, String reason) {
        return scheduleMapper.releasePendingTurn(
                schedule.getWorkspaceId(), schedule.getId(), schedule.getPendingTurnId(),
                delivered, reason, nowUtc()) == 1;
    }

    /**
     * Releases the pending brief as delivered and writes its notification as one durable act.
     *
     * <p>The release is the at-most-once claim, so it must precede delivery within the same
     * transaction rather than follow it: releasing first but committing separately would mark a brief
     * delivered whose notification then failed to persist, and because the release clears the pending
     * fields no later sweep would have anything left to retry. Sharing one transaction keeps
     * "delivered" true exactly when a notification exists — a failed write rolls the release back and
     * the brief stays pending for the next pass.
     *
     * @return {@link Outcome#DELIVERED} when this pass released and announced the brief,
     *     {@link Outcome#SKIPPED} when another pass had already released it
     */
    private Outcome releaseAndNotify(AiBriefSchedule schedule, User owner) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Boolean delivered = transaction.execute(status -> {
            if (!release(schedule, true, null)) {
                return false;
            }
            notify(schedule, owner);
            return true;
        });
        return Boolean.TRUE.equals(delivered) ? Outcome.DELIVERED : Outcome.SKIPPED;
    }

    /**
     * Writes the one notification a delivered brief produces.
     *
     * <p>Notifications own read, dismiss, and snooze state, so the brief itself carries none: this
     * row is a pointer to the session the member can open, and the session is where the evidence
     * lives. The dedupe key is the durable turn, so no pass can announce the same brief twice.
     *
     * <p>The stored title exists because the column requires one, not because it is displayed: the
     * inbox renders this notification from its type and data so it reads in the member's own
     * language whatever the snapshot says.
     */
    private void notify(AiBriefSchedule schedule, User owner) {
        Notification notification = new Notification();
        notification.setWorkspaceId(schedule.getWorkspaceId());
        notification.setRecipientId(owner.getId());
        notification.setType(NOTIFICATION_TYPE);
        notification.setCategory(NOTIFICATION_CATEGORY);
        notification.setSeverity(NOTIFICATION_SEVERITY);
        notification.setTemplateVersion(1);
        notification.setTitle(WEEKLY.equals(schedule.getPendingKind())
                ? "Weekly review ready"
                : "Daily brief ready");
        notification.setSourceType("ai_chat_session");
        notification.setSourceId(schedule.getPendingSessionId());
        notification.setDedupeKey(
                "ai.brief:" + schedule.getPendingKind() + ":" + schedule.getPendingTurnId());
        notification.setTriggeredAt(nowUtc());
        notification.setActionUrl("/ask-connex/" + schedule.getPendingSessionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", schedule.getPendingKind());
        data.put("sessionId", schedule.getPendingSessionId());
        notification.setData(objectMapper.writeValueAsString(data));
        notificationDelivery.deliver(notification);
    }

    /**
     * The literal request a scheduled run sends, in the owner's own language.
     *
     * <p>It is deliberately a sentence the deterministic skill router recognizes rather than a
     * private flag, so a scheduled brief and a member typing the same request take exactly the same
     * path, select the same skill version, and can be compared against each other afterwards. That is
     * also why it must be localized: the sentence is persisted as the member's own turn and shown in
     * the transcript, so an English literal would put words a Japanese member never wrote into their
     * own session. Each localized form is chosen to match the same work-brief patterns the catalog
     * declares, so routing is identical in either language.
     */
    private static AiChatTurnCreateRequest turnRequest(String kind, Locale locale) {
        boolean weekly = WEEKLY.equals(kind);
        return new AiChatTurnCreateRequest(
                japanese(locale)
                        ? (weekly ? "今週のブリーフをお願いします。" : "今日のブリーフをお願いします。")
                        : (weekly ? "Give me my weekly review." : "Give me my daily brief."),
                List.of(),
                new AiChatQueryScopeRequest(
                        null, null,
                        weekly
                                ? AiChatScopeBounds.BRIEF_WEEKLY_PERIOD_DAYS
                                : AiChatScopeBounds.BRIEF_DAILY_PERIOD_DAYS,
                        "me", List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), null));
    }

    /**
     * The durable session title, written in the owner's language.
     *
     * <p>The title is not incidental metadata: it is what the member sees in their own session list
     * for as long as the brief is retained, so it answers to their locale exactly as the transcript
     * does.
     */
    private static AiChatSessionCreateRequest sessionRequest(
            String kind, LocalDate claimOn, Locale locale) {
        boolean weekly = WEEKLY.equals(kind);
        String prefix = japanese(locale)
                ? (weekly ? "週次レビュー " : "デイリーブリーフ ")
                : (weekly ? "Weekly review " : "Daily brief ");
        AiChatSessionCreateRequest request = new AiChatSessionCreateRequest();
        request.setTitle(prefix + claimOn);
        request.setAutoTitle(false);
        return request;
    }

    private static Locale locale(User owner) {
        String declared = owner.getLocale();
        return declared != null && declared.toLowerCase(Locale.ROOT).startsWith("ja")
                ? Locale.JAPANESE
                : Locale.ENGLISH;
    }

    private static boolean japanese(Locale locale) {
        return locale != null && Locale.JAPANESE.getLanguage().equals(locale.getLanguage());
    }

    private String nowUtc() {
        return MYSQL_TIMESTAMP.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
