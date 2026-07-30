package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStakeholder;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskAnalyticsDto;
import ooo.klae.connex.backend.dto.DealRiskCurrencySummaryDto;
import ooo.klae.connex.backend.dto.DashboardDealRiskResult;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.DealRiskFactorCountDto;
import ooo.klae.connex.backend.dto.DealTouchDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.util.DateTimes;

/**
 * Assesses deal risk on read, layering a small set of deterministic signals over the deal timeline,
 * the expected close date, and stakeholder warmth.
 *
 * <p>This is the deal-facing counterpart to {@link ScoringService}: where that scores relationship
 * warmth per contact, this composes those warmth bands with a deal's own schedule and silence into
 * per-deal risk. Nothing is persisted — every read recomputes from the current data.
 *
 * <p>A deal is assessed only while open ({@code closed_at IS NULL}) and not opted out of risk
 * evaluation ({@code risk_excluded}, issue #358); an excluded deal reads as {@code none}. A
 * stakeholder who is opted out likewise contributes no cold-relationship factor, though they still
 * count as a stakeholder for {@code no_stakeholders}. Each signal that fires becomes
 * a {@link DealRiskFactor}; the overall {@link DealRiskDto#getLevel() level} is the highest severity
 * among them, and a bounded composite {@link DealRiskDto#getScore() score} orders at-risk deals.
 * A deal's "last touch" is the most recent of its activities, notes, tasks, or — as a floor so a
 * brand-new deal is not flagged as stalled — its creation time.
 *
 * <p>The two silence signals are layered rather than additive: an imminent-but-quiet close
 * ({@code closing_soon_quiet}) subsumes plain staleness, so {@code stalled} is suppressed when it
 * fires. Plain close-date proximity that is <em>not</em> quiet is intentionally left to the existing
 * {@code deal.close} reminder rather than duplicated here.
 *
 * <p>Every read is workspace-scoped; the caller resolves the active workspace and passes it in.
 */
@Service
@RequiredArgsConstructor
public class DealRiskService {
    private final DealMapper dealMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final ScoringService scoringService;
    private final Clock clock;

    private static final String HIGH = "high";
    private static final String MEDIUM = "medium";
    private static final String LOW = "low";
    private static final String NONE = "none";

    private static final String CODE_CLOSE_OVERDUE = "close_overdue";
    private static final String CODE_CLOSING_SOON_QUIET = "closing_soon_quiet";
    private static final String CODE_STALLED = "stalled";
    private static final String CODE_STAKEHOLDER_COLD = "stakeholder_cold";
    private static final String CODE_NO_STAKEHOLDERS = "no_stakeholders";

    /** Days out to the expected close date within which a close counts as imminent. */
    private static final int CLOSING_SOON_DAYS = 14;
    /** Silence (days since last touch) that turns an imminent close into a high-risk signal. */
    private static final int CLOSING_SOON_QUIET_DAYS = 14;
    /** Silence (days since last touch) at which an open deal is considered stalled. */
    private static final int STALLED_DAYS = 30;

    /** Stakeholder roles that escalate a cooling relationship into a sharper deal-risk signal. */
    private static final Set<String> KEY_ROLE_KEYWORDS = Set.of("champion", "decision", "buyer", "sponsor");
    private static final String COLD_BAND = "cold";
    private static final String COOL_BAND = "cool";
    private static final String COOLING_TREND = "cooling";

    private static final int SCORE_HIGH = 50;
    private static final int SCORE_MEDIUM = 25;
    private static final int SCORE_LOW = 10;
    private static final int MAX_INTERACTIVE_CANDIDATES = 1_000;
    private static final int WARMTH_BATCH_SIZE = 1_000;

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * One deal-risk assessment plus a clock-stable fingerprint of the persisted inputs that
     * produced it.
     */
    public record NotificationRiskState(DealRiskDto assessment, String sourceStateHash) {}

    /**
     * Assesses every open deal in the workspace and returns only those with at least one risk
     * factor, ordered by descending {@link DealRiskDto#getScore() score}.
     */
    public List<DealRiskDto> assessWorkspace(int workspaceId) {
        return assessWorkspace(workspaceId, warmthByPerson(workspaceId));
    }

    /**
     * As {@link #assessWorkspace(int)}, but reuses an already-computed warmth map keyed by person
     * id when the caller has one — the scheduled notification sweep scores the workspace once and
     * shares that map across its passes, avoiding a second full rescore here.
     */
    public List<DealRiskDto> assessWorkspace(int workspaceId, Map<Integer, RelationshipTemperatureDto> warmth) {
        return assessWorkspaceNotificationStates(
                workspaceId,
                warmth,
                Map.of()).stream()
            .map(NotificationRiskState::assessment)
            .filter(assessment -> !assessment.getFactors().isEmpty())
            .sorted(Comparator.comparingInt(DealRiskDto::getScore).reversed())
            .toList();
    }

    /**
     * Assesses every eligible open deal for notification reconciliation, including deals whose
     * current assessment has no factors.
     */
    public List<NotificationRiskState> assessWorkspaceNotificationStates(
            int workspaceId,
            Map<Integer, RelationshipTemperatureDto> warmth,
            Map<Integer, String> contactSourceStateHashes) {
        Instant now = Instant.now(clock);
        String assessedAt = utc(now);
        List<Deal> open = dealMapper.getAllDeals(workspaceId).stream()
            .filter(deal -> isOpen(deal) && !deal.isRiskExcluded())
            .toList();
        DealTouchState touchState = dealTouchState(workspaceId, open, now.toEpochMilli());
        Map<Integer, List<DealStakeholder>> stakeholders = stakeholdersByDeal(workspaceId);

        List<NotificationRiskState> out = new ArrayList<>();
        for (Deal deal : open) {
            DealRiskDto assessment = assess(
                deal,
                now,
                touchState.effectiveLastTouch(),
                stakeholders,
                warmth,
                assessedAt);
            out.add(new NotificationRiskState(
                assessment,
                notificationSourceStateHash(
                    deal,
                    touchState.sourceStateHashes().get(deal.getId()),
                    stakeholders.getOrDefault(deal.getId(), List.of()),
                    contactSourceStateHashes,
                    warmth)));
        }
        out.sort(Comparator.comparingInt(
            state -> state.assessment().getDealId()));
        return out;
    }

    /**
     * Assesses only the requested workspace-owned deals with batched, deal-scoped interaction and
     * stakeholder reads.
     * @param workspaceId active workspace
     * @param dealIds bounded deal ids
     * @return at-risk assessments ordered by descending score
     */
    public List<DealRiskDto> assessDeals(int workspaceId, List<Integer> dealIds) {
        return assessDeals(workspaceId, dealIds, null);
    }

    /** Assesses requested deals while optionally reusing a complete contact-warmth snapshot. */
    public List<DealRiskDto> assessDeals(
            int workspaceId,
            List<Integer> dealIds,
            Map<Integer, RelationshipTemperatureDto> knownWarmth) {
        if (dealIds == null || dealIds.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        String assessedAt = utc(now);
        List<Deal> open = dealMapper.getByIds(workspaceId, dealIds).stream()
            .filter(deal -> isOpen(deal) && !deal.isRiskExcluded())
            .toList();
        if (open.isEmpty()) {
            return List.of();
        }
        List<Integer> openIds = open.stream().map(Deal::getId).toList();
        Map<Integer, Long> lastTouch = new HashMap<>();
        for (Deal deal : open) {
            merge(lastTouch, deal.getId(), notFuture(epoch(deal.getCreatedAt()), now.toEpochMilli()));
        }
        for (DealTouchDto touch : dealMapper.getLatestDealTouches(workspaceId, openIds, assessedAt)) {
            merge(lastTouch, touch.dealId(), epoch(touch.touchedAt()));
        }
        Map<Integer, List<DealStakeholder>> stakeholders = new HashMap<>();
        Set<Integer> personIds = new HashSet<>();
        for (DealStakeholder stakeholder : dealMapper.getDealStakeholdersByDealIds(workspaceId, openIds)) {
            stakeholders.computeIfAbsent(stakeholder.getDealId(), key -> new ArrayList<>()).add(stakeholder);
            personIds.add(stakeholder.getPersonId());
        }
        Map<Integer, RelationshipTemperatureDto> warmth = knownWarmth == null
            ? warmthFor(workspaceId, personIds)
            : knownWarmth;
        List<DealRiskDto> assessments = new ArrayList<>();
        for (Deal deal : open) {
            DealRiskDto assessment = assess(deal, now, lastTouch, stakeholders, warmth, assessedAt);
            if (!assessment.getFactors().isEmpty()) {
                assessments.add(assessment);
            }
        }
        assessments.sort(Comparator.comparingInt(DealRiskDto::getScore).reversed());
        return assessments;
    }

    /** Returns the highest-risk dashboard deals from a fixed candidate ceiling. */
    public DashboardDealRiskResult assessDashboard(
            int workspaceId,
            Map<Integer, RelationshipTemperatureDto> warmth,
            int limit) {
        RiskCandidateBatch candidates = riskCandidates(workspaceId, MemberScope.allTeam());
        List<DealRiskDto> items = assessDeals(workspaceId, candidates.ids(), warmth)
            .stream().limit(limit).toList();
        return new DashboardDealRiskResult(items, candidates.truncated());
    }

    /** Returns compact per-currency analytics over a fixed interactive candidate ceiling. */
    public DealRiskAnalyticsDto analytics(int workspaceId, MemberScope memberScope) {
        RiskCandidateBatch candidates = riskCandidates(workspaceId, memberScope);
        Map<String, List<DealRiskDto>> byCurrency = new HashMap<>();
        for (DealRiskDto risk : assessDeals(workspaceId, candidates.ids())) {
            String currency = risk.getCurrency() == null || risk.getCurrency().isBlank()
                ? "USD"
                : risk.getCurrency();
            byCurrency.computeIfAbsent(currency, key -> new ArrayList<>()).add(risk);
        }
        List<DealRiskCurrencySummaryDto> currencies = byCurrency.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> currencySummary(entry.getKey(), entry.getValue()))
            .toList();
        return new DealRiskAnalyticsDto(currencies, candidates.truncated());
    }

    private RiskCandidateBatch riskCandidates(int workspaceId, MemberScope memberScope) {
        List<Integer> ids = dealMapper.getRiskCandidateIds(
            workspaceId, memberScope, MAX_INTERACTIVE_CANDIDATES + 1);
        boolean truncated = ids.size() > MAX_INTERACTIVE_CANDIDATES;
        return new RiskCandidateBatch(
            truncated ? ids.subList(0, MAX_INTERACTIVE_CANDIDATES) : ids,
            truncated);
    }

    private static DealRiskCurrencySummaryDto currencySummary(
            String currency,
            List<DealRiskDto> risks) {
        Map<String, Long> factorCounts = new HashMap<>();
        double value = 0;
        long high = 0;
        long medium = 0;
        long low = 0;
        for (DealRiskDto risk : risks) {
            value += risk.getValue();
            if (HIGH.equals(risk.getLevel())) high++;
            else if (MEDIUM.equals(risk.getLevel())) medium++;
            else if (LOW.equals(risk.getLevel())) low++;
            for (DealRiskFactor factor : risk.getFactors()) {
                factorCounts.merge(factor.getCode(), 1L, Long::sum);
            }
        }
        List<DealRiskFactorCountDto> factors = factorCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new DealRiskFactorCountDto(entry.getKey(), entry.getValue()))
            .toList();
        return new DealRiskCurrencySummaryDto(
            currency, value, risks.size(), high, medium, low, factors);
    }

    private record RiskCandidateBatch(List<Integer> ids, boolean truncated) {}

    /**
     * Assesses a single deal. Returns a {@code "none"} assessment when the deal does not exist in the
     * workspace, is already closed, or is opted out of risk evaluation.
     */
    public DealRiskDto assessDeal(int workspaceId, int dealId) {
        Instant now = Instant.now(clock);
        String assessedAt = utc(now);
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) {
            return new DealRiskDto(dealId, 0.0, null, NONE, 0, List.of(), assessedAt);
        }
        if (!isOpen(deal) || deal.isRiskExcluded()) {
            return new DealRiskDto(
                dealId, deal.getValue(), deal.getCurrency(), NONE, 0, List.of(), assessedAt);
        }
        List<DealStakeholder> dealStakeholders = dealMapper.getDealStakeholdersByDealId(workspaceId, dealId);
        Map<Integer, List<DealStakeholder>> stakeholders = Map.of(dealId, dealStakeholders);
        Map<Integer, Long> lastTouch = dealTouch(workspaceId, deal, now.toEpochMilli());
        Set<Integer> personIds = new HashSet<>();
        for (DealStakeholder person : dealStakeholders) {
            personIds.add(person.getPersonId());
        }
        Map<Integer, RelationshipTemperatureDto> warmth = warmthFor(workspaceId, personIds);
        return assess(deal, now, lastTouch, stakeholders, warmth, assessedAt);
    }

    /**
     * Most recent touch for a single deal, in epoch millis, seeded with its creation time. Scopes
     * the interaction scan to the deal rather than loading every activity/note/task in the workspace.
     */
    private Map<Integer, Long> dealTouch(int workspaceId, Deal deal, long nowMs) {
        Map<Integer, Long> last = new HashMap<>();
        int id = deal.getId();
        merge(last, id, notFuture(epoch(deal.getCreatedAt()), nowMs));
        for (Activity activity : activityMapper.getActivitiesByDealId(workspaceId, id)) {
            merge(last, id, notFuture(epoch(activity.getTimestamp()), nowMs));
        }
        for (Note note : noteMapper.getNotesByDealId(workspaceId, id)) {
            if (!isSharedNote(note)) continue;
            merge(last, id, notFuture(epoch(note.getCreatedAt()), nowMs));
        }
        for (Task task : taskMapper.getTasksByDealId(workspaceId, id)) {
            merge(last, id, notFuture(epoch(task.getCreatedAt()), nowMs));
        }
        return last;
    }

    private Map<Integer, RelationshipTemperatureDto> warmthFor(int workspaceId, Set<Integer> personIds) {
        Map<Integer, RelationshipTemperatureDto> map = new HashMap<>();
        List<Integer> ids = personIds.stream().sorted().toList();
        for (int offset = 0; offset < ids.size(); offset += WARMTH_BATCH_SIZE) {
            Set<Integer> batch = new HashSet<>(
                ids.subList(offset, Math.min(ids.size(), offset + WARMTH_BATCH_SIZE)));
            for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId, batch)) {
                map.put(temperature.getId(), temperature);
            }
        }
        return map;
    }

    private DealRiskDto assess(
        Deal deal,
        Instant now,
        Map<Integer, Long> lastTouch,
        Map<Integer, List<DealStakeholder>> stakeholders,
        Map<Integer, RelationshipTemperatureDto> warmth,
        String assessedAt
    ) {
        List<DealRiskFactor> factors = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        Long touchMs = lastTouch.get(deal.getId());
        Integer daysSinceTouch = touchMs == null ? null
            : (int) ChronoUnit.DAYS.between(LocalDate.ofInstant(Instant.ofEpochMilli(touchMs), ZoneOffset.UTC), today);

        boolean closingSoonQuiet = false;
        LocalDate close = parseDate(deal.getExpectedCloseDate());
        if (close != null && close.isBefore(today)) {
            factors.add(factor(CODE_CLOSE_OVERDUE, HIGH,
                Map.of("daysOverdue", ChronoUnit.DAYS.between(close, today))));
        } else if (close != null && !close.isAfter(today.plusDays(CLOSING_SOON_DAYS))
                && (daysSinceTouch == null || daysSinceTouch >= CLOSING_SOON_QUIET_DAYS)) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("daysUntilClose", ChronoUnit.DAYS.between(today, close));
            if (daysSinceTouch != null) {
                params.put("daysSinceTouch", daysSinceTouch);
            }
            factors.add(factor(CODE_CLOSING_SOON_QUIET, HIGH, params));
            closingSoonQuiet = true;
        }

        if (!closingSoonQuiet && daysSinceTouch != null && daysSinceTouch >= STALLED_DAYS) {
            factors.add(factor(CODE_STALLED, MEDIUM, Map.of("daysSinceTouch", daysSinceTouch)));
        }

        List<DealStakeholder> people = stakeholders.getOrDefault(deal.getId(), List.of());
        if (people.isEmpty()) {
            factors.add(factor(CODE_NO_STAKEHOLDERS, LOW, Map.of()));
        } else {
            for (DealStakeholder person : people) {
                DealRiskFactor coldFactor = stakeholderColdFactor(person, warmth.get(person.getPersonId()));
                if (coldFactor != null) {
                    factors.add(coldFactor);
                }
            }
        }

        factors.sort(Comparator.comparingInt(f -> severityRank(f.getSeverity())));
        return new DealRiskDto(
            deal.getId(), deal.getValue(), deal.getCurrency(),
            overallLevel(factors), score(factors), factors, assessedAt);
    }

    /**
     * A cooling-stakeholder factor for one deal contact, or {@code null} when the contact is warm
     * enough not to warrant one or is opted out of risk evaluation. A cold band is sharper than a
     * cooling-but-still-cool band, and a key role (champion, decision maker, buyer, sponsor)
     * escalates either by one step.
     */
    private DealRiskFactor stakeholderColdFactor(DealStakeholder person, RelationshipTemperatureDto warmth) {
        if (person.isRiskExcluded()) {
            return null;
        }
        if (warmth == null) {
            return null;
        }
        boolean cold = COLD_BAND.equals(warmth.getBand());
        boolean cooling = COOL_BAND.equals(warmth.getBand()) && COOLING_TREND.equals(warmth.getTrend());
        if (!cold && !cooling) {
            return null;
        }
        boolean key = keyRole(person.getRole());
        String severity = cold ? (key ? HIGH : MEDIUM) : (key ? MEDIUM : LOW);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("personId", person.getPersonId());
        params.put("person", person.getPersonLabel());
        if (person.getRole() != null && !person.getRole().isBlank()) {
            params.put("role", person.getRole());
        }
        params.put("band", warmth.getBand());
        if (warmth.getDaysSinceTouch() != null) {
            params.put("daysSinceTouch", warmth.getDaysSinceTouch());
        }
        return factor(CODE_STAKEHOLDER_COLD, severity, params);
    }

    private static String overallLevel(List<DealRiskFactor> factors) {
        if (factors.stream().anyMatch(f -> HIGH.equals(f.getSeverity()))) {
            return HIGH;
        }
        if (factors.stream().anyMatch(f -> MEDIUM.equals(f.getSeverity()))) {
            return MEDIUM;
        }
        return factors.isEmpty() ? NONE : LOW;
    }

    /**
     * Bounded composite score. Cold stakeholders contribute only their single highest weight rather
     * than one per stakeholder, so a deal's rank reflects the severity of its distinct problems
     * rather than how many contacts it happens to carry.
     */
    private static int score(List<DealRiskFactor> factors) {
        int score = 0;
        int stakeholderColdWeight = 0;
        for (DealRiskFactor factor : factors) {
            int weight = weight(factor.getSeverity());
            if (CODE_STAKEHOLDER_COLD.equals(factor.getCode())) {
                stakeholderColdWeight = Math.max(stakeholderColdWeight, weight);
            } else {
                score += weight;
            }
        }
        return Math.min(100, score + stakeholderColdWeight);
    }

    private static int weight(String severity) {
        return switch (severity) {
            case HIGH -> SCORE_HIGH;
            case MEDIUM -> SCORE_MEDIUM;
            default -> SCORE_LOW;
        };
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case HIGH -> 0;
            case MEDIUM -> 1;
            default -> 2;
        };
    }

    private static DealRiskFactor factor(String code, String severity, Map<String, Object> params) {
        return new DealRiskFactor(code, severity, params);
    }

    /**
     * Most recent touch per deal, in epoch millis, seeded with each deal's creation time so a fresh
     * deal with no logged interactions is measured from when it was created rather than treated as
     * infinitely quiet. Future-dated timestamps are ignored so a stray forward-dated interaction
     * cannot make a genuinely quiet deal read as freshly touched.
     */
    private DealTouchState dealTouchState(int workspaceId, List<Deal> deals, long nowMs) {
        Map<Integer, Long> effective = new HashMap<>();
        Map<Integer, List<DealSourceTouch>> sourceTouches = new HashMap<>();
        for (Deal deal : deals) {
            merge(
                effective,
                deal.getId(),
                notFuture(epoch(deal.getCreatedAt()), nowMs));
        }
        for (Activity activity : activityMapper.getAllActivities(workspaceId)) {
            mergeTouchState(
                effective,
                sourceTouches,
                dealId(activity.getDeal()),
                "activity",
                activity.getId(),
                epoch(activity.getTimestamp()),
                activity.getTimestamp(),
                nowMs);
        }
        for (Note note : noteMapper.getAllNotes(workspaceId)) {
            if (!isSharedNote(note)) continue;
            mergeTouchState(
                effective,
                sourceTouches,
                dealId(note.getDeal()),
                "note",
                note.getId(),
                epoch(note.getCreatedAt()),
                note.getCreatedAt(),
                nowMs);
        }
        for (Task task : taskMapper.getAllTasks(workspaceId)) {
            mergeTouchState(
                effective,
                sourceTouches,
                dealId(task.getDeal()),
                "task",
                task.getId(),
                epoch(task.getCreatedAt()),
                task.getCreatedAt(),
                nowMs);
        }
        Map<Integer, String> sourceStateHashes = new HashMap<>();
        sourceTouches.forEach((dealId, touches) ->
            sourceStateHashes.put(dealId, dealSourceStateHash(touches)));
        return new DealTouchState(effective, sourceStateHashes);
    }

    private record DealTouchState(
        Map<Integer, Long> effectiveLastTouch,
        Map<Integer, String> sourceStateHashes
    ) {}

    private record DealSourceTouch(
        String kind,
        int id,
        String timestamp
    ) {}

    private static void mergeTouchState(
            Map<Integer, Long> effective,
            Map<Integer, List<DealSourceTouch>> sourceTouches,
            Integer dealId,
            String kind,
            int sourceId,
            Long epochMillis,
            String timestamp,
            long nowMs) {
        if (dealId == null || epochMillis == null) {
            return;
        }
        sourceTouches.computeIfAbsent(dealId, key -> new ArrayList<>()).add(
            new DealSourceTouch(kind, sourceId, timestamp));
        merge(effective, dealId, notFuture(epochMillis, nowMs));
    }

    private static String notificationSourceStateHash(
            Deal deal,
            String dealTouchSourceStateHash,
            List<DealStakeholder> stakeholders,
            Map<Integer, String> contactSourceStateHashes,
            Map<Integer, RelationshipTemperatureDto> warmth) {
        List<String> values = new ArrayList<>();
        values.add(Integer.toString(deal.getId()));
        values.add(Double.toString(deal.getValue()));
        values.add(deal.getCurrency());
        values.add(deal.getExpectedCloseDate());
        values.add(deal.getCreatedAt());
        values.add(deal.getUpdatedAt());
        values.add(dealTouchSourceStateHash);
        stakeholders.stream()
            .sorted(Comparator
                .comparingInt(DealStakeholder::getPersonId)
                .thenComparing(
                    DealStakeholder::getRole,
                    Comparator.nullsFirst(String::compareTo))
                .thenComparing(DealStakeholder::isRiskExcluded))
            .forEach(stakeholder -> {
                values.add(Integer.toString(stakeholder.getPersonId()));
                values.add(stakeholder.getRole());
                values.add(Boolean.toString(stakeholder.isRiskExcluded()));
                values.add(Boolean.toString(
                    warmth.containsKey(stakeholder.getPersonId())));
                values.add(contactSourceStateHashes.getOrDefault(
                    stakeholder.getPersonId(),
                    ScoringService.emptyContactSourceStateHash()));
            });
        return hashValues(values);
    }

    private static String dealSourceStateHash(List<DealSourceTouch> sourceTouches) {
        List<String> values = new ArrayList<>();
        sourceTouches.stream()
            .sorted(Comparator
                .comparing(DealSourceTouch::kind)
                .thenComparingInt(DealSourceTouch::id)
                .thenComparing(DealSourceTouch::timestamp))
            .forEach(touch -> {
                values.add(touch.kind());
                values.add(Integer.toString(touch.id()));
                values.add(touch.timestamp());
            });
        return hashValues(values);
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

    private static Long notFuture(Long epochMillis, long nowMs) {
        return (epochMillis == null || epochMillis > nowMs) ? null : epochMillis;
    }

    private Map<Integer, List<DealStakeholder>> stakeholdersByDeal(int workspaceId) {
        Map<Integer, List<DealStakeholder>> map = new HashMap<>();
        for (DealStakeholder person : dealMapper.getAllDealStakeholders(workspaceId)) {
            map.computeIfAbsent(person.getDealId(), key -> new ArrayList<>()).add(person);
        }
        return map;
    }

    private Map<Integer, RelationshipTemperatureDto> warmthByPerson(int workspaceId) {
        Map<Integer, RelationshipTemperatureDto> map = new HashMap<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
            map.put(temperature.getId(), temperature);
        }
        return map;
    }

    private static void merge(Map<Integer, Long> last, Integer dealId, Long epochMillis) {
        if (dealId == null || epochMillis == null) {
            return;
        }
        last.merge(dealId, epochMillis, Math::max);
    }

    private static boolean isOpen(Deal deal) {
        return deal.getClosedAt() == null;
    }

    private static boolean isSharedNote(Note note) {
        return "workspace".equals(note.getVisibility());
    }

    private static boolean keyRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        return KEY_ROLE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private static Integer dealId(Deal deal) {
        return (deal == null || deal.getId() == 0) ? null : deal.getId();
    }

    /** Parses an expected-close {@code yyyy-MM-dd} date, tolerating an incidental time part; null if unparseable. */
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            trimmed = trimmed.substring(0, space);
        }
        int t = trimmed.indexOf('T');
        if (t > 0) {
            trimmed = trimmed.substring(0, t);
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Long epoch(String value) {
        return DateTimes.epochMillis(value);
    }

    private static String utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(MYSQL_DATETIME);
    }
}
