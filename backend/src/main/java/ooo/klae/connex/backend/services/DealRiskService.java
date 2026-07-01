package ooo.klae.connex.backend.services;

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
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

/**
 * Assesses deal risk on read, layering a small set of deterministic signals over the deal timeline,
 * the expected close date, and stakeholder warmth.
 *
 * <p>This is the deal-facing counterpart to {@link ScoringService}: where that scores relationship
 * warmth per contact, this composes those warmth bands with a deal's own schedule and silence into
 * per-deal risk. Nothing is persisted — every read recomputes from the current data.
 *
 * <p>A deal is assessed only while open ({@code closed_at IS NULL}). Each signal that fires becomes
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

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Assesses every open deal in the workspace and returns only those with at least one risk
     * factor, ordered by descending {@link DealRiskDto#getScore() score}.
     */
    public List<DealRiskDto> assessWorkspace(int workspaceId) {
        Instant now = Instant.now(clock);
        String assessedAt = utc(now);
        List<Deal> open = dealMapper.getAllDeals(workspaceId).stream().filter(DealRiskService::isOpen).toList();
        Map<Integer, Long> lastTouch = dealLastTouch(workspaceId, open, now.toEpochMilli());
        Map<Integer, List<DealStakeholder>> stakeholders = stakeholdersByDeal(workspaceId);
        Map<Integer, RelationshipTemperatureDto> warmth = warmthByPerson(workspaceId);

        List<DealRiskDto> out = new ArrayList<>();
        for (Deal deal : open) {
            DealRiskDto assessment = assess(deal, now, lastTouch, stakeholders, warmth, assessedAt);
            if (!assessment.getFactors().isEmpty()) {
                out.add(assessment);
            }
        }
        out.sort(Comparator.comparingInt(DealRiskDto::getScore).reversed());
        return out;
    }

    /**
     * Assesses a single deal. Returns a {@code "none"} assessment when the deal does not exist in the
     * workspace or is already closed.
     */
    public DealRiskDto assessDeal(int workspaceId, int dealId) {
        Instant now = Instant.now(clock);
        String assessedAt = utc(now);
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null || !isOpen(deal)) {
            return new DealRiskDto(dealId, NONE, 0, List.of(), assessedAt);
        }
        Map<Integer, Long> lastTouch = dealLastTouch(workspaceId, List.of(deal), now.toEpochMilli());
        Map<Integer, List<DealStakeholder>> stakeholders = stakeholdersByDeal(workspaceId);
        Map<Integer, RelationshipTemperatureDto> warmth = warmthByPerson(workspaceId);
        return assess(deal, now, lastTouch, stakeholders, warmth, assessedAt);
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
        return new DealRiskDto(deal.getId(), overallLevel(factors), score(factors), factors, assessedAt);
    }

    /**
     * A cooling-stakeholder factor for one deal contact, or {@code null} when the contact is warm
     * enough not to warrant one. A cold band is sharper than a cooling-but-still-cool band, and a
     * key role (champion, decision maker, buyer, sponsor) escalates either by one step.
     */
    private DealRiskFactor stakeholderColdFactor(DealStakeholder person, RelationshipTemperatureDto warmth) {
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
    private Map<Integer, Long> dealLastTouch(int workspaceId, List<Deal> deals, long nowMs) {
        Map<Integer, Long> last = new HashMap<>();
        for (Deal deal : deals) {
            merge(last, deal.getId(), notFuture(epoch(deal.getCreatedAt()), nowMs));
        }
        for (Activity activity : activityMapper.getAllActivities(workspaceId)) {
            merge(last, dealId(activity.getDeal()), notFuture(epoch(activity.getTimestamp()), nowMs));
        }
        for (Note note : noteMapper.getAllNotes(workspaceId)) {
            merge(last, dealId(note.getDeal()), notFuture(epoch(note.getCreatedAt()), nowMs));
        }
        for (Task task : taskMapper.getAllTasks(workspaceId)) {
            merge(last, dealId(task.getDeal()), notFuture(epoch(task.getCreatedAt()), nowMs));
        }
        return last;
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

    /** Parses a UTC {@code yyyy-MM-dd HH:mm:ss} (or ISO-ish) datetime to epoch millis, tolerantly. */
    private static Long epoch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('T', ' ');
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.length() == 10) {
            normalized = normalized + " 00:00:00";
        } else if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        try {
            return LocalDateTime.parse(normalized, MYSQL_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(MYSQL_DATETIME);
    }
}
