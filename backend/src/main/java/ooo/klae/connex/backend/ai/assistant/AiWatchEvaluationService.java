package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiWatch;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides, deterministically, whether each watch fired — and delivers the one notification it earns.
 *
 * <p>No model participates. Every condition here is read from the system that owns it: the warmth
 * model behind Radar, the task projection behind My Work, the deterministic deal risk model. The
 * watch contributes a threshold, a cooldown, and a delivery. AI synthesis is deliberately downstream
 * of all of this: the notification links to the record, whose existing Ask Connex entry can then be
 * asked for an explanation of the same evidence. A provider outage therefore cannot delay, corrupt,
 * or lose a trigger.
 *
 * <p>Each evaluation runs under the owner's freshly resolved identity, so a member who has lost
 * access to a watched record, or membership of the workspace, simply stops resolving the subject and
 * the watch stops firing. Firing itself is a compare-and-set on the durable watch row, which is what
 * makes repeated evaluation, replay, and multi-instance sweeps produce at most one notification per
 * state token per cooldown.
 *
 * <p>Although no model participates, a watch is an Ask Connex surface and is governed as one: the
 * same fail-closed feature gate that governs an interactive turn is consulted under the owner's
 * identity before anything is evaluated. An instance kill switch, a workspace whose organization has
 * disabled the assistant, or an owner who has lost {@code AI_USE} therefore stops the firing stream
 * entirely rather than leaving a deterministic side channel that keeps delivering.
 */
@Service
@RequiredArgsConstructor
public class AiWatchEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(AiWatchEvaluationService.class);
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final String NOTIFICATION_TYPE = "ai.watch.fired";
    private static final String NOTIFICATION_CATEGORY = "assistant";
    private static final String INFO = "info";
    private static final String WARNING = "warning";

    /** Coldest-last warmth ordering, so "at or colder than" is a single index comparison. */
    private static final List<String> BAND_ORDER = List.of("hot", "warm", "cool", "cold");

    /** Ascending risk severity, so "at or above" is a single index comparison. */
    private static final List<String> LEVEL_ORDER = List.of("none", "low", "medium", "high");

    private final AiWatchMapper watchMapper;
    private final AiFeatureGate featureGate;
    private final AiWatchSubjectReader subjectReader;
    private final TaskMapper taskMapper;
    private final ScoringService scoringService;
    private final DealRiskService dealRiskService;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final NotificationDelivery notificationDelivery;
    private final AiGenerationContextRunner contextRunner;
    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** What one watch evaluation did. */
    public enum Outcome { FIRED, QUIET, SKIPPED }

    /**
     * Evaluates one watch under its owner's current identity.
     *
     * @param watch the durable watch, already read in the workspace's catalog
     * @return what the evaluation did
     */
    public Outcome evaluate(AiWatch watch) {
        User owner;
        try {
            owner = userService.getActiveWorkspaceUser(
                    watch.getWorkspaceId(), watch.getOwnerUserId());
        } catch (RuntimeException exception) {
            return Outcome.SKIPPED;
        }
        Outcome[] outcome = { Outcome.SKIPPED };
        contextRunner.run(
                watch.getWorkspaceId(), watch.getOwnerUserId(), locale(owner),
                () -> outcome[0] = evaluateAsOwner(watch, owner));
        return outcome[0];
    }

    private Outcome evaluateAsOwner(AiWatch watch, User owner) {
        if (!featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)) {
            // Nothing is recorded as evaluated either: a workspace whose assistant is switched off
            // has not checked the condition, and claiming it did would misstate the watch's own
            // inspectable history the moment the assistant is switched back on.
            return Outcome.SKIPPED;
        }
        AiWatchType type = AiWatchType.from(watch.getWatchType()).orElse(null);
        if (type == null || !type.subjectKinds().contains(watch.getSubjectKind())) {
            // A stored pair the current build no longer evaluates — a retired type, or a subject
            // kind this type never reads — evaluates to nothing rather than falling through to a
            // branch that would silently read the wrong source.
            return Outcome.SKIPPED;
        }
        Optional<String> label = subjectReader.label(watch.getSubjectKind(), watch.getSubjectId());
        if (label.isEmpty()) {
            // The record is gone, archived, or no longer processable. Recording the evaluation and
            // firing nothing is the correct reconciliation: the member keeps an inspectable watch
            // whose subject they can see has become unreadable, rather than a silent alert stream.
            watchMapper.recordEvaluated(watch.getWorkspaceId(), watch.getId(), nowUtc());
            return Outcome.SKIPPED;
        }
        Firing firing = switch (type) {
            case RELATIONSHIP_COOLING -> cooling(watch);
            case NO_INTERACTION -> quiet(watch);
            case COMMITMENT_OVERDUE -> overdue(watch);
            case DEAL_RISK_THRESHOLD -> risk(watch);
        };
        watchMapper.recordEvaluated(watch.getWorkspaceId(), watch.getId(), nowUtc());
        if (firing == null) {
            return Outcome.QUIET;
        }
        if (watchMapper.claimFiring(
                watch.getWorkspaceId(), watch.getId(), firing.state(),
                cooldownCutoff(watch), nowUtc()) != 1) {
            return Outcome.QUIET;
        }
        notify(watch, owner, label.get(), firing, today());
        return Outcome.FIRED;
    }

    /**
     * The warmth watch: fires when the record's authoritative band is at or colder than the declared
     * one. The band is Radar's, unchanged; only the comparison belongs to the watch.
     */
    private Firing cooling(AiWatch watch) {
        RelationshipTemperatureDto score = warmth(watch);
        if (score == null || watch.getThresholdBand() == null) {
            return null;
        }
        int current = BAND_ORDER.indexOf(score.getBand());
        int declared = BAND_ORDER.indexOf(watch.getThresholdBand());
        if (current < 0 || declared < 0 || current < declared) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("band", score.getBand());
        evidence.put("trend", score.getTrend());
        evidence.put("warmthScore", score.getScore());
        if (score.getDaysSinceTouch() != null) {
            evidence.put("daysSinceTouch", score.getDaysSinceTouch());
        }
        evidence.put("modelVersion", score.getModelVersion());
        return new Firing(
                "band:" + score.getBand(),
                "cold".equals(score.getBand()) ? WARNING : INFO,
                evidence);
    }

    /**
     * The silence watch: fires when the warmth model reports no qualifying touch for at least the
     * declared number of days. The state token is the declared threshold rather than the live day
     * count, so a relationship that stays quiet re-announces itself once per cooldown instead of once
     * per day.
     */
    private Firing quiet(AiWatch watch) {
        RelationshipTemperatureDto score = warmth(watch);
        Integer declared = watch.getThresholdDays();
        if (score == null || declared == null) {
            return null;
        }
        Integer days = score.getDaysSinceTouch();
        if (days == null || days < declared) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("daysSinceTouch", days);
        evidence.put("thresholdDays", declared);
        if (score.getLastTouchAt() != null) {
            evidence.put("lastTouchAt", score.getLastTouchAt());
        }
        evidence.put("modelVersion", score.getModelVersion());
        return new Firing("quiet:" + declared, INFO, evidence);
    }

    /**
     * The commitment watch: fires when the task projection reports an open, past-due task linked to
     * the record, whoever it is assigned to. The watch is about the record rather than about the
     * owner's own queue — My Work and the daily brief already answer that — so the stated trigger
     * says "whoever it is assigned to" and the read is deliberately unscoped by assignee.
     *
     * <p>The state token is the oldest overdue due date, so a newly overdue task that becomes the
     * oldest is a new token and re-announces immediately; an unchanged backlog keeps its token and
     * can only re-announce once its cooldown has elapsed.
     */
    private Firing overdue(AiWatch watch) {
        Integer personId = "person".equals(watch.getSubjectKind()) ? watch.getSubjectId() : null;
        Integer companyId = "company".equals(watch.getSubjectKind()) ? watch.getSubjectId() : null;
        Integer dealId = "deal".equals(watch.getSubjectKind()) ? watch.getSubjectId() : null;
        AiWatchOverdueCommitments state = taskMapper.countOverdueForSubject(
                watch.getWorkspaceId(), personId, companyId, dealId, today(),
                workspaceScopeControlAccess.getForWorkspace(watch.getWorkspaceId()).workspaceIds());
        if (state == null || state.overdueCount() <= 0 || state.earliestDueDate() == null) {
            return null;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("overdueCount", state.overdueCount());
        evidence.put("earliestDueDate", state.earliestDueDate());
        return new Firing("overdue:" + state.earliestDueDate(), WARNING, evidence);
    }

    /**
     * The deal risk watch: fires when the deterministic risk model reports the deal at or above the
     * declared level. The factor codes travel as evidence exactly as the model emitted them, so the
     * notification names the same reasons the deal page does.
     */
    private Firing risk(AiWatch watch) {
        if (!"deal".equals(watch.getSubjectKind()) || watch.getThresholdLevel() == null) {
            return null;
        }
        DealRiskDto assessment = dealRiskService.assessDeal(
                watch.getWorkspaceId(), watch.getSubjectId());
        if (assessment == null) {
            return null;
        }
        int current = LEVEL_ORDER.indexOf(assessment.getLevel());
        int declared = LEVEL_ORDER.indexOf(watch.getThresholdLevel());
        if (current < 0 || declared < 0 || current < declared) {
            return null;
        }
        List<String> factors = assessment.getFactors() == null
                ? List.of()
                : assessment.getFactors().stream().map(DealRiskFactor::getCode).toList();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("level", assessment.getLevel());
        evidence.put("riskScore", assessment.getScore());
        evidence.put("factors", factors);
        evidence.put("assessedAt", assessment.getAssessedAt());
        return new Firing(
                "risk:" + assessment.getLevel(),
                "high".equals(assessment.getLevel()) ? WARNING : INFO,
                evidence);
    }

    private RelationshipTemperatureDto warmth(AiWatch watch) {
        Set<Integer> ids = Set.of(watch.getSubjectId());
        List<RelationshipTemperatureDto> scores = "company".equals(watch.getSubjectKind())
                ? scoringService.scoreCompanies(watch.getWorkspaceId(), ids)
                : scoringService.scoreContacts(watch.getWorkspaceId(), ids);
        return scores.stream()
                .filter(score -> score.getId() == watch.getSubjectId())
                .findFirst()
                .orElse(null);
    }

    /**
     * Writes the one notification a firing earns.
     *
     * <p>The notification names the rule, the threshold, the record, when the condition was
     * evaluated, and the evidence behind it. It never says that Ask Connex noticed something: the
     * deep link goes to the record, where the source-owned evidence lives and where the existing Ask
     * Connex entry point can be asked to explain it.
     *
     * <p>The dedupe key carries the local date the firing was claimed on as well as the state token.
     * The token alone is a deliberately closed space — a relationship that stays cold produces
     * {@code band:cold} forever — so keying only on it would upsert the same inbox row every time
     * and a member who read or dismissed the first alert would never see the condition raised again.
     * Adding the claim date makes each cooldown window a distinct notification, which is what the
     * cooldown was always documented to mean, while the compare-and-set on the watch row still
     * allows exactly one claim per window and so cannot flood.
     *
     * @param firedOn the owner-calendar date this firing was claimed on
     */
    private void notify(AiWatch watch, User owner, String label, Firing firing, LocalDate firedOn) {
        Notification notification = new Notification();
        notification.setWorkspaceId(watch.getWorkspaceId());
        notification.setRecipientId(owner.getId());
        notification.setType(NOTIFICATION_TYPE);
        notification.setCategory(NOTIFICATION_CATEGORY);
        notification.setSeverity(firing.severity());
        notification.setTemplateVersion(1);
        notification.setTitle("Watch triggered");
        notification.setSourceType(sourceType(watch.getSubjectKind()));
        notification.setSourceId(watch.getSubjectId());
        notification.setSourceLabel(label);
        notification.setDedupeKey(
                "ai.watch:" + watch.getId() + ":" + firing.state() + ":" + firedOn);
        notification.setTriggeredAt(nowUtc());
        String prefix = switch (watch.getSubjectKind()) {
            case "person" -> "/records/contacts/";
            case "company" -> "/records/companies/";
            default -> "/records/deals/";
        };
        notification.setActionUrl(prefix + watch.getSubjectId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("watchId", watch.getId());
        data.put("watchType", watch.getWatchType());
        data.put("subjectKind", watch.getSubjectKind());
        data.put("subjectId", watch.getSubjectId());
        data.put("thresholdBand", watch.getThresholdBand());
        data.put("thresholdDays", watch.getThresholdDays());
        data.put("thresholdLevel", watch.getThresholdLevel());
        data.put("evaluatedAt", nowUtc());
        data.put("evidence", firing.evidence());
        notification.setData(objectMapper.writeValueAsString(data));
        try {
            notificationDelivery.deliver(notification);
        } catch (RuntimeException exception) {
            log.warn("Watch notification delivery failed workspace={} exceptionClass={}",
                    watch.getWorkspaceId(), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private static String sourceType(String subjectKind) {
        return switch (subjectKind) {
            case "person" -> "person";
            case "company" -> "company";
            default -> "deal";
        };
    }

    private String cooldownCutoff(AiWatch watch) {
        return MYSQL_TIMESTAMP.format(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                        .minusDays(Math.max(1, watch.getCooldownDays())));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(AiChatScopeCalendar.zone(workspaceService)));
    }

    private String nowUtc() {
        return MYSQL_TIMESTAMP.format(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private static Locale locale(User owner) {
        String declared = owner.getLocale();
        return declared != null && declared.toLowerCase(Locale.ROOT).startsWith("ja")
                ? Locale.JAPANESE
                : Locale.ENGLISH;
    }

    /**
     * One decided firing.
     *
     * @param state deterministic token that must change before the same watch fires again
     * @param severity notification severity the condition warrants
     * @param evidence source-owned figures the notification restates
     */
    private record Firing(String state, String severity, Map<String, Object> evidence) {
        private Firing {
            evidence = Map.copyOf(evidence);
        }
    }
}
