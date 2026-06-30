package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

/**
 * Computes relationship "temperature" (warmth) for contacts and companies on read.
 *
 * <p>This is the single source of truth for warmth across the app: the relationship map
 * colours nodes by band, the dashboard surfaces cooling relationships, and the records
 * tables expose a sortable warmth column — all from the scores produced here.
 *
 * <p>Warmth is a recency-decayed, intent-weighted sum of logged interactions. Each touch
 * starts at a base weight (meeting &gt; call &gt; email &gt; note &gt; task) and decays by
 * half every {@link #HALF_LIFE_DAYS}. The decayed sum is squashed into 0–100 so the score
 * saturates rather than growing without bound for very active relationships.
 *
 * <p>Every read is workspace-scoped; the caller resolves the active workspace and passes it in.
 */
@Service
@RequiredArgsConstructor
public class ScoringService {
    private final PersonMapper personMapper;
    private final CompanyMapper companyMapper;
    private final DealMapper dealMapper;
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final Clock clock;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Half-life of a single touch's contribution, in days. */
    private static final double HALF_LIFE_DAYS = 30.0;
    /** Decayed-weight sum that maps to a ~63 score; tunes how quickly warmth saturates. */
    private static final double SATURATION = 0.7;
    /** Score at/below which a relationship is "cold"; also the target for predictive decay. */
    private static final int COLD_SCORE = 15;
    /** Decayed-weight sum at which the score reaches {@link #COLD_SCORE}; the predictive decay floor. */
    private static final double RAW_COLD = -SATURATION * log2(1.0 - COLD_SCORE / 100.0);
    /** Recent window (days) used for trend detection and the contextual touch count. */
    private static final int RECENT_WINDOW_DAYS = 21;
    /** Prior window (days) compared against the recent window to detect cooling. */
    private static final int PRIOR_WINDOW_DAYS = 120;
    /** Minimum prior-window weight for a relationship to count as "was warm" when cooling. */
    private static final double COOLING_PRIOR_MIN = 0.8;

    private static final double NOTE_WEIGHT = 0.4;
    private static final double TASK_WEIGHT = 0.3;

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A single timestamped, intent-weighted interaction. */
    private record Touch(long epochMillis, double weight) {}

    /**
     * Scores every contact in the workspace as of now, including those with no activity (band "cold").
     */
    public List<RelationshipTemperatureDto> scoreContacts(int workspaceId) {
        return computeContactScores(workspaceId, Instant.now(clock).toEpochMilli(), Long.MAX_VALUE);
    }

    /**
     * Scores every contact in the workspace as of {@code asOf}: warmth is decayed against that
     * instant and only interactions logged on or before it are counted. Used by the time-travel
     * replay to reconstruct historical warmth — touches dated after {@code asOf} are excluded so a
     * future-dated interaction can never leak into a past frame.
     */
    public List<RelationshipTemperatureDto> scoreContacts(int workspaceId, Instant asOf) {
        long t = asOf.toEpochMilli();
        return computeContactScores(workspaceId, t, t);
    }

    private List<RelationshipTemperatureDto> computeContactScores(int workspaceId, long reference, long cutoff) {
        Map<Integer, List<Touch>> byPerson = collectContactTouches(workspaceId);
        List<Person> persons = personMapper.getAllPersons(workspaceId);
        List<RelationshipTemperatureDto> out = new ArrayList<>(persons.size());
        for (Person p : persons) {
            out.add(temperature(p.getId(), byPerson.getOrDefault(p.getId(), List.of()), reference, cutoff));
        }
        return out;
    }

    /** Buckets every contact-linked touch (activities, notes, tasks) by contact id. */
    private Map<Integer, List<Touch>> collectContactTouches(int workspaceId) {
        Map<Integer, List<Touch>> byPerson = new HashMap<>();
        for (Activity a : activityMapper.getAllActivities(workspaceId)) {
            Integer pid = personId(a.getPerson());
            Long ts = epoch(a.getTimestamp());
            if (pid != null && ts != null) add(byPerson, pid, new Touch(ts, activityWeight(a.getType())));
        }
        for (Note n : noteMapper.getAllNotes(workspaceId)) {
            Integer pid = personId(n.getPerson());
            Long ts = epoch(n.getCreatedAt());
            if (pid != null && ts != null) add(byPerson, pid, new Touch(ts, NOTE_WEIGHT));
        }
        for (Task t : taskMapper.getAllTasks(workspaceId)) {
            Integer pid = personId(t.getPerson());
            Long ts = epoch(t.getCreatedAt());
            if (pid != null && ts != null) add(byPerson, pid, new Touch(ts, TASK_WEIGHT));
        }
        return byPerson;
    }

    /**
     * Scores every company in the workspace as of now. A touch counts toward a company when it is
     * linked to one of the company's contacts or one of its deals (mirroring how engagement metrics
     * are scoped elsewhere).
     */
    public List<RelationshipTemperatureDto> scoreCompanies(int workspaceId) {
        return computeCompanyScores(workspaceId, Instant.now(clock).toEpochMilli(), Long.MAX_VALUE);
    }

    /**
     * Scores every company in the workspace as of {@code asOf}: warmth is decayed against that
     * instant and only interactions logged on or before it are counted. Company attribution uses
     * present-day contact/deal parentage; the time-travel replay refines attribution to the
     * as-of-{@code asOf} employment when it assembles frames.
     */
    public List<RelationshipTemperatureDto> scoreCompanies(int workspaceId, Instant asOf) {
        long t = asOf.toEpochMilli();
        return computeCompanyScores(workspaceId, t, t);
    }

    private List<RelationshipTemperatureDto> computeCompanyScores(int workspaceId, long reference, long cutoff) {
        Map<Integer, List<Touch>> byCompany = collectCompanyTouches(workspaceId);
        List<Company> companies = companyMapper.getAllCompanies(workspaceId);
        List<RelationshipTemperatureDto> out = new ArrayList<>(companies.size());
        for (Company c : companies) {
            out.add(temperature(c.getId(), byCompany.getOrDefault(c.getId(), List.of()), reference, cutoff));
        }
        return out;
    }

    /** Buckets every touch by the company of its linked contact and/or deal (present-day parentage). */
    private Map<Integer, List<Touch>> collectCompanyTouches(int workspaceId) {
        Map<Integer, Integer> personCompany = new HashMap<>();
        for (Person p : personMapper.getAllPersons(workspaceId)) {
            Integer cid = p.getCompany() == null ? null : p.getCompany().getId();
            if (cid != null && cid != 0) personCompany.put(p.getId(), cid);
        }
        Map<Integer, Integer> dealCompany = new HashMap<>();
        for (Deal d : dealMapper.getAllDeals(workspaceId)) {
            if (d.getCompanyId() != null) dealCompany.put(d.getId(), d.getCompanyId());
        }

        Map<Integer, List<Touch>> byCompany = new HashMap<>();
        for (Activity a : activityMapper.getAllActivities(workspaceId)) {
            Long ts = epoch(a.getTimestamp());
            if (ts != null) attribute(a.getPerson(), a.getDeal(), new Touch(ts, activityWeight(a.getType())),
                personCompany, dealCompany, byCompany);
        }
        for (Note n : noteMapper.getAllNotes(workspaceId)) {
            Long ts = epoch(n.getCreatedAt());
            if (ts != null) attribute(n.getPerson(), n.getDeal(), new Touch(ts, NOTE_WEIGHT),
                personCompany, dealCompany, byCompany);
        }
        for (Task t : taskMapper.getAllTasks(workspaceId)) {
            Long ts = epoch(t.getCreatedAt());
            if (ts != null) attribute(t.getPerson(), t.getDeal(), new Touch(ts, TASK_WEIGHT),
                personCompany, dealCompany, byCompany);
        }
        return byCompany;
    }

    /**
     * Convenience map of contact id → warmth score, used to order the contacts records page by
     * warmth without duplicating the scoring formula.
     */
    public Map<Integer, Integer> contactScoreMap(int workspaceId) {
        Map<Integer, Integer> map = new HashMap<>();
        for (RelationshipTemperatureDto d : scoreContacts(workspaceId)) map.put(d.getId(), d.getScore());
        return map;
    }

    /**
     * Warmth band for every contact with any touch, at each instant in {@code frameMillis} — decayed
     * against that instant and counting only touches logged on or before it. Touches load once; the
     * map for a frame omits contacts with no qualifying touch (the caller defaults them to "cold").
     * Used by the time-travel replay to avoid re-querying per frame.
     */
    public List<Map<Integer, String>> contactBandFrames(int workspaceId, long[] frameMillis) {
        Map<Integer, List<Touch>> byPerson = collectContactTouches(workspaceId);
        List<Map<Integer, String>> frames = new ArrayList<>(frameMillis.length);
        for (long t : frameMillis) {
            Map<Integer, String> bands = new HashMap<>();
            for (Map.Entry<Integer, List<Touch>> e : byPerson.entrySet()) {
                bands.put(e.getKey(), temperature(e.getKey(), e.getValue(), t, t).getBand());
            }
            frames.add(bands);
        }
        return frames;
    }

    /**
     * Warmth band for every company at each instant in {@code frameMillis}. A contact's touch is
     * attributed to the company that contact worked at <em>when the touch occurred</em> (via
     * {@code employerAt}), so a contact moving employers cools their old account and heats the new
     * one rather than retroactively re-crediting their whole history; a deal's touch uses the deal's
     * present-day company. Attribution is fixed once and touches load once. The map for a frame omits
     * companies with no qualifying touch (the caller defaults them to "cold").
     */
    public List<Map<Integer, String>> companyBandFrames(int workspaceId, long[] frameMillis,
            EmployerResolver employerAt) {
        List<AttributedTouch> touches =
            attributeCompanyTouches(collectCompanyAttributableTouches(workspaceId), employerAt);
        List<Map<Integer, String>> frames = new ArrayList<>(frameMillis.length);
        for (long t : frameMillis) {
            Map<Integer, List<Touch>> byCompany = new HashMap<>();
            for (AttributedTouch at : touches) {
                if (at.epochMillis() > t) continue;
                for (Integer cid : at.companies()) add(byCompany, cid, new Touch(at.epochMillis(), at.weight()));
            }
            Map<Integer, String> bands = new HashMap<>();
            for (Map.Entry<Integer, List<Touch>> e : byCompany.entrySet()) {
                bands.put(e.getKey(), temperature(e.getKey(), e.getValue(), t, t).getBand());
            }
            frames.add(bands);
        }
        return frames;
    }

    /** Resolves which company a contact worked at at a given instant, for touch-time attribution. */
    @FunctionalInterface
    public interface EmployerResolver {
        /** The company the contact worked at at {@code epochMillis}, or {@code null} if none/unknown. */
        Integer employerAt(int personId, long epochMillis);
    }

    /** A touch carrying the linkage needed to attribute it to a company as of any instant. */
    private record CompanyTouch(long epochMillis, double weight, Integer personId, Integer dealCompanyId) {}

    /** A touch with its target companies fixed by the contact's employer at the touch's own time. */
    private record AttributedTouch(long epochMillis, double weight, Set<Integer> companies) {}

    /** Fixes each touch's target companies once, by the contact's employer at the touch's own time. */
    private static List<AttributedTouch> attributeCompanyTouches(List<CompanyTouch> touches,
            EmployerResolver employerAt) {
        List<AttributedTouch> out = new ArrayList<>(touches.size());
        for (CompanyTouch ct : touches) {
            Set<Integer> companies = new HashSet<>();
            if (ct.personId() != null) {
                Integer cid = employerAt.employerAt(ct.personId(), ct.epochMillis());
                if (cid != null) companies.add(cid);
            }
            if (ct.dealCompanyId() != null) companies.add(ct.dealCompanyId());
            if (!companies.isEmpty()) out.add(new AttributedTouch(ct.epochMillis(), ct.weight(), companies));
        }
        return out;
    }

    /** Collects every touch with its contact and present-day deal-company linkage, for as-of attribution. */
    private List<CompanyTouch> collectCompanyAttributableTouches(int workspaceId) {
        Map<Integer, Integer> dealCompany = new HashMap<>();
        for (Deal d : dealMapper.getAllDeals(workspaceId)) {
            if (d.getCompanyId() != null) dealCompany.put(d.getId(), d.getCompanyId());
        }
        List<CompanyTouch> out = new ArrayList<>();
        for (Activity a : activityMapper.getAllActivities(workspaceId)) {
            Long ts = epoch(a.getTimestamp());
            if (ts != null) out.add(new CompanyTouch(ts, activityWeight(a.getType()),
                personId(a.getPerson()), dealCompanyFor(a.getDeal(), dealCompany)));
        }
        for (Note n : noteMapper.getAllNotes(workspaceId)) {
            Long ts = epoch(n.getCreatedAt());
            if (ts != null) out.add(new CompanyTouch(ts, NOTE_WEIGHT,
                personId(n.getPerson()), dealCompanyFor(n.getDeal(), dealCompany)));
        }
        for (Task t : taskMapper.getAllTasks(workspaceId)) {
            Long ts = epoch(t.getCreatedAt());
            if (ts != null) out.add(new CompanyTouch(ts, TASK_WEIGHT,
                personId(t.getPerson()), dealCompanyFor(t.getDeal(), dealCompany)));
        }
        return out;
    }

    private static Integer dealCompanyFor(Deal deal, Map<Integer, Integer> dealCompany) {
        Integer did = dealId(deal);
        return did == null ? null : dealCompany.get(did);
    }

    /**
     * Collapses a contact's or company's touches into a single temperature reading, decayed against
     * {@code reference} and counting only touches timestamped at or before {@code cutoff}. Pass
     * {@code Long.MAX_VALUE} as the cutoff for a live reading; the replay passes the frame instant so
     * a future-dated touch is skipped before the age clamp and never leaks into a past frame.
     */
    private RelationshipTemperatureDto temperature(int id, List<Touch> touches, long reference, long cutoff) {
        double raw = 0, recent = 0, prior = 0;
        long lastTs = Long.MIN_VALUE;
        int recentCount = 0;
        boolean any = false;
        for (Touch t : touches) {
            if (t.epochMillis() > cutoff) continue;
            any = true;
            double ageDays = Math.max(0.0, (reference - t.epochMillis()) / (double) DAY_MS);
            raw += t.weight() * Math.pow(2.0, -ageDays / HALF_LIFE_DAYS);
            if (ageDays <= RECENT_WINDOW_DAYS) {
                recent += t.weight();
                recentCount++;
            } else if (ageDays <= PRIOR_WINDOW_DAYS) {
                prior += t.weight();
            }
            if (t.epochMillis() > lastTs) lastTs = t.epochMillis();
        }
        if (!any) {
            return new RelationshipTemperatureDto(id, 0, "cold", "steady", null, null, 0, null, null);
        }

        int score = (int) Math.round(100.0 * (1.0 - Math.pow(2.0, -raw / SATURATION)));
        score = Math.max(0, Math.min(100, score));
        String band = score >= 60 ? "hot" : score >= 35 ? "warm" : score >= 15 ? "cool" : "cold";

        long daysSince = (reference - lastTs) / DAY_MS;
        String trend;
        if (prior >= COOLING_PRIOR_MIN && recent < prior * 0.5 && daysSince >= RECENT_WINDOW_DAYS) {
            trend = "cooling";
        } else if (recent > prior) {
            trend = "rising";
        } else {
            trend = "steady";
        }

        String lastTouchAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastTs), ZoneOffset.UTC)
            .format(MYSQL_DATETIME);

        Integer daysUntilCold = null;
        String goesColdAt = null;
        if (raw > RAW_COLD) {
            double daysToCold = HALF_LIFE_DAYS * log2(raw / RAW_COLD);
            daysUntilCold = (int) Math.round(daysToCold);
            goesColdAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(reference + Math.round(daysToCold * DAY_MS)), ZoneOffset.UTC).format(MYSQL_DATETIME);
        }

        return new RelationshipTemperatureDto(id, score, band, trend, lastTouchAt, (int) daysSince, recentCount,
            goesColdAt, daysUntilCold);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    /** Attributes a touch to its contact's company and/or its deal's company (deduplicated). */
    private void attribute(Person person, Deal deal, Touch touch,
            Map<Integer, Integer> personCompany, Map<Integer, Integer> dealCompany,
            Map<Integer, List<Touch>> byCompany) {
        Set<Integer> companies = new HashSet<>();
        Integer pid = personId(person);
        if (pid != null) {
            Integer cid = personCompany.get(pid);
            if (cid != null) companies.add(cid);
        }
        Integer did = dealId(deal);
        if (did != null) {
            Integer cid = dealCompany.get(did);
            if (cid != null) companies.add(cid);
        }
        for (Integer cid : companies) add(byCompany, cid, touch);
    }

    private static void add(Map<Integer, List<Touch>> map, int key, Touch touch) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(touch);
    }

    private static double activityWeight(String type) {
        if (type == null) return 0.5;
        return switch (type.toLowerCase()) {
            case "meeting" -> 1.0;
            case "call" -> 0.8;
            case "email" -> 0.6;
            default -> 0.5;
        };
    }

    private static Integer personId(Person p) {
        return (p == null || p.getId() == 0) ? null : p.getId();
    }

    private static Integer dealId(Deal d) {
        return (d == null || d.getId() == 0) ? null : d.getId();
    }

    /** Parses a UTC {@code yyyy-MM-dd HH:mm:ss} (or ISO-ish) datetime to epoch millis, tolerantly. */
    private static Long epoch(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().replace('T', ' ');
        int dot = v.indexOf('.');
        if (dot > 0) v = v.substring(0, dot);
        if (v.endsWith("Z")) v = v.substring(0, v.length() - 1).trim();
        try {
            return LocalDateTime.parse(v, MYSQL_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
