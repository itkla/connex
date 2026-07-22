package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.WarmPathBridgeDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The "receive side" of the relationship graph (issue #614): surfaces warm introduction paths for
 * the user — a target contact worth reaching (dormant, or imported and never engaged) plus the
 * bridges, contacts the team is warm with who can make the introduction.
 *
 * <p>Bridge-to-target evidence is tiered and always labeled, never blended away: an explicit
 * {@code person_edge} is verified; a shared current employer or a dated tenure overlap at a past
 * employer are inferred, at lower confidence. Paths are scored multiplicatively — bridge warmth
 * times evidence confidence times the coldness gap being closed — so a path is never stronger
 * than its weakest link. The feed unit is the target: one row per target with the best few
 * bridges. Accepting a path creates a follow-up task; dismissals persist per avenue or per
 * target. Every read and write is workspace-scoped, and both roles honor {@code intro_excluded}
 * and suspension.
 */
@Service
@RequiredArgsConstructor
public class WarmPathService {
    private final IntroductionMapper introductionMapper;
    private final PersonEdgeMapper edgeMapper;
    private final PersonMapper personMapper;
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final TaskService taskService;

    /** A dormant relationship must be untouched at least this long to surface as a re-warm target. */
    private static final int MIN_DORMANT_DAYS = 30;
    /** Companies with more eligible contacts than this are too large for an inferred colleague
     *  tie to mean anything; their pairs are skipped (also bounds the pairwise work). */
    private static final int MAX_COMPANY_FANOUT = 40;
    /** Stints considered per person per employer, bounding the overlap scan against contacts whose
     *  employment history has churned through many rows at the same company. */
    private static final int MAX_STINTS_PER_EMPLOYER = 5;
    /** Bridges shown per target row. */
    private static final int MAX_BRIDGES_PER_TARGET = 3;
    /** Rows a single bridge may appear in, so the feed never becomes "ask one person about everyone". */
    private static final int MAX_ROWS_PER_BRIDGE = 3;

    private static final double CONF_COLLEAGUES = 0.75;
    private static final double CONF_FORMER_COLLEAGUES = 0.45;
    private static final double CONF_EDGE_WEAK = 0.85;
    private static final double CONF_EDGE_DEFAULT = 0.95;
    private static final double CONF_EDGE_STRONG = 1.0;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final String BAND_HOT = "hot";
    private static final String BAND_WARM = "warm";
    private static final String COLD_BAND = "cold";

    static final String EVIDENCE_CONNECTION = "connection";
    static final String EVIDENCE_COLLEAGUES = "colleagues";
    static final String EVIDENCE_FORMER_COLLEAGUES = "former_colleagues";
    static final String REACH_REWARM = "rewarm";
    static final String REACH_NEW = "reach";

    private static final String STATUS_DISMISSED = "dismissed";
    private static final String STATUS_ACCEPTED = "accepted";

    /** Ranked warm introduction paths for the active workspace. */
    public List<WarmPathDto> getPaths(int limit) {
        return computePaths(workspaceService.getCurrentWorkspaceId(), resolveLimit(limit));
    }

    /** Clamps a requested row limit to the feed's default/maximum bounds. */
    static int resolveLimit(int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /**
     * Ranked warm paths for the given workspace. Takes the workspace id explicitly so it can run
     * off the request thread, mirroring {@code IntroductionService.computeSuggestions}.
     */
    public List<WarmPathDto> computePaths(int workspaceId, int limit) {
        return computePaths(workspaceId, limit, null);
    }

    /**
     * As {@link #computePaths(int, int)}, but reuses an already-computed warmth map when the
     * caller has one. A {@code null} map is scored on demand.
     */
    public List<WarmPathDto> computePaths(
            int workspaceId, int limit, Map<Integer, RelationshipTemperatureDto> temperatures) {
        if (limit <= 0) {
            return List.of();
        }
        List<IntroCandidatePerson> candidates = introductionMapper.findWarmPathCandidates(workspaceId);
        if (candidates.size() < 2) {
            return List.of();
        }
        List<PersonEdge> edges = edgeMapper.getAllEdges(workspaceId);
        List<IntroEmploymentRow> employment = introductionMapper.findWorkspaceEmployment(workspaceId);
        List<WarmPathDismissal> dismissals = introductionMapper.findWarmPathDismissals(workspaceId);
        Map<Integer, RelationshipTemperatureDto> warmth = temperatures;
        if (warmth == null) {
            warmth = new HashMap<>();
            for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
                warmth.put(temperature.getId(), temperature);
            }
        }
        return rankPaths(candidates, edges, employment, dismissals, warmth, limit);
    }

    /**
     * Pure ranking over already-loaded workspace data, so it can be unit-tested without a
     * database. Builds the tiered bridge-to-target evidence, generates directional paths from
     * every warm bridge to every dormant or never-engaged target, scores each multiplicatively,
     * groups by target, and applies the per-bridge fatigue cap before returning the top
     * {@code limit} rows by descending score.
     */
    static List<WarmPathDto> rankPaths(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment,
            List<WarmPathDismissal> dismissals,
            Map<Integer, RelationshipTemperatureDto> temperatures,
            int limit) {
        if (limit <= 0 || candidates.size() < 2) {
            return List.of();
        }
        Map<Integer, IntroCandidatePerson> byId = new HashMap<>();
        for (IntroCandidatePerson candidate : candidates) {
            byId.put(candidate.getId(), candidate);
        }

        Set<Integer> bridges = new HashSet<>();
        Set<Integer> targets = new HashSet<>();
        for (IntroCandidatePerson candidate : candidates) {
            RelationshipTemperatureDto temperature = temperatures.get(candidate.getId());
            if (bridgeEligible(temperature)) {
                bridges.add(candidate.getId());
            }
            if (targetEligible(temperature)) {
                targets.add(candidate.getId());
            }
        }
        if (bridges.isEmpty() || targets.isEmpty()) {
            return List.of();
        }

        Map<Long, Evidence> evidence = collectEvidence(candidates, edges, employment, byId.keySet());
        if (evidence.isEmpty()) {
            return List.of();
        }

        Set<Integer> dismissedTargets = new HashSet<>();
        Set<Long> dismissedPaths = new HashSet<>();
        for (WarmPathDismissal dismissal : dismissals) {
            if (dismissal.getBridgePersonId() == null) {
                dismissedTargets.add(dismissal.getTargetPersonId());
            } else {
                dismissedPaths.add(directedKey(dismissal.getBridgePersonId(), dismissal.getTargetPersonId()));
            }
        }

        Map<Integer, List<WarmPathBridgeDto>> byTarget = new HashMap<>();
        for (Map.Entry<Long, Evidence> entry : evidence.entrySet()) {
            int personX = (int) (entry.getKey() >> 32);
            int personY = (int) (entry.getKey() & 0xffffffffL);
            addPath(byTarget, byId, temperatures, entry.getValue(), personX, personY,
                bridges, targets, dismissedTargets, dismissedPaths);
            addPath(byTarget, byId, temperatures, entry.getValue(), personY, personX,
                bridges, targets, dismissedTargets, dismissedPaths);
        }

        List<WarmPathDto> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<WarmPathBridgeDto>> entry : byTarget.entrySet()) {
            List<WarmPathBridgeDto> ranked = entry.getValue();
            ranked.sort(Comparator
                .comparingInt(WarmPathBridgeDto::getScore).reversed()
                .thenComparingInt(WarmPathBridgeDto::getPersonId));
            rows.add(row(byId.get(entry.getKey()), temperatures, ranked));
        }
        rows.sort(ROW_ORDER);

        List<WarmPathDto> capped = capBridgeFatigue(rows);
        capped.sort(ROW_ORDER);
        return capped.size() > limit ? new ArrayList<>(capped.subList(0, limit)) : capped;
    }

    private static final Comparator<WarmPathDto> ROW_ORDER = Comparator
        .comparingInt(WarmPathDto::getScore).reversed()
        .thenComparing(WarmPathDto::getReachType, Comparator.reverseOrder())
        .thenComparingInt(WarmPathDto::getTargetId);

    /**
     * Enforces the per-bridge fatigue cap over the ranked rows: each bridge may appear in at most
     * {@link #MAX_ROWS_PER_BRIDGE} rows (allocated strongest-row-first), a row keeps at most
     * {@link #MAX_BRIDGES_PER_TARGET} bridges, and a row that loses every bridge is dropped.
     * Capping can lower a row's score (its best bridge may have been used up), so the caller
     * re-sorts before applying the row limit.
     */
    private static List<WarmPathDto> capBridgeFatigue(List<WarmPathDto> rows) {
        Map<Integer, Integer> bridgeUse = new HashMap<>();
        List<WarmPathDto> out = new ArrayList<>();
        for (WarmPathDto candidateRow : rows) {
            List<WarmPathBridgeDto> kept = new ArrayList<>();
            for (WarmPathBridgeDto bridge : candidateRow.getBridges()) {
                if (kept.size() >= MAX_BRIDGES_PER_TARGET) {
                    break;
                }
                if (bridgeUse.getOrDefault(bridge.getPersonId(), 0) < MAX_ROWS_PER_BRIDGE) {
                    kept.add(bridge);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            for (WarmPathBridgeDto bridge : kept) {
                bridgeUse.merge(bridge.getPersonId(), 1, Integer::sum);
            }
            candidateRow.setBridges(kept);
            candidateRow.setScore(kept.get(0).getScore());
            out.add(candidateRow);
        }
        return out;
    }

    private static void addPath(
            Map<Integer, List<WarmPathBridgeDto>> byTarget,
            Map<Integer, IntroCandidatePerson> byId,
            Map<Integer, RelationshipTemperatureDto> temperatures,
            Evidence evidence,
            int bridgeId,
            int targetId,
            Set<Integer> bridges,
            Set<Integer> targets,
            Set<Integer> dismissedTargets,
            Set<Long> dismissedPaths) {
        if (!bridges.contains(bridgeId) || !targets.contains(targetId)
                || dismissedTargets.contains(targetId)
                || dismissedPaths.contains(directedKey(bridgeId, targetId))) {
            return;
        }
        double bridgeWarmth = score(temperatures, bridgeId) / 100.0;
        double gap = (100 - score(temperatures, targetId)) / 100.0;
        int pathScore = (int) Math.round(100.0 * bridgeWarmth * evidence.confidence() * gap);
        if (pathScore <= 0) {
            return;
        }
        IntroCandidatePerson bridge = byId.get(bridgeId);
        WarmPathBridgeDto dto = new WarmPathBridgeDto();
        dto.setPersonId(bridge.getId());
        dto.setName(bridge.getName());
        dto.setTitle(bridge.getTitle());
        dto.setCompany(bridge.getCompanyName());
        dto.setImageUrl(bridge.getImageUrl());
        dto.setWarmth(band(temperatures, bridgeId));
        dto.setEvidenceType(evidence.type());
        dto.setEvidenceCompany(evidence.company());
        dto.setOverlapStartYear(evidence.overlapStartYear());
        dto.setOverlapEndYear(evidence.overlapEndYear());
        dto.setScore(pathScore);
        byTarget.computeIfAbsent(targetId, key -> new ArrayList<>()).add(dto);
    }

    private static WarmPathDto row(
            IntroCandidatePerson target,
            Map<Integer, RelationshipTemperatureDto> temperatures,
            List<WarmPathBridgeDto> rankedBridges) {
        RelationshipTemperatureDto temperature = temperatures.get(target.getId());
        WarmPathDto dto = new WarmPathDto();
        dto.setTargetId(target.getId());
        dto.setTargetName(target.getName());
        dto.setTargetTitle(target.getTitle());
        dto.setTargetCompany(target.getCompanyName());
        dto.setTargetImageUrl(target.getImageUrl());
        dto.setTargetWarmth(band(temperatures, target.getId()));
        dto.setTargetDaysSinceTouch(temperature == null ? null : temperature.getDaysSinceTouch());
        dto.setReachType(neverEngaged(temperature) ? REACH_NEW : REACH_REWARM);
        dto.setScore(rankedBridges.get(0).getScore());
        dto.setBridges(rankedBridges);
        return dto;
    }

    /**
     * The tiered bridge-to-target evidence for every candidate pair, keeping the highest
     * confidence tier when several apply: explicit edges (verified), shared current employer
     * (inferred), dated tenure overlap at a shared past employer (inferred, weakest).
     */
    private static Map<Long, Evidence> collectEvidence(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment,
            Set<Integer> candidateIds) {
        Map<Long, Evidence> evidence = new HashMap<>();

        for (PersonEdge edge : edges) {
            if (!candidateIds.contains(edge.getSourcePersonId())
                    || !candidateIds.contains(edge.getTargetPersonId())
                    || edge.getSourcePersonId() == edge.getTargetPersonId()) {
                continue;
            }
            evidence.merge(pairKey(edge.getSourcePersonId(), edge.getTargetPersonId()),
                new Evidence(EVIDENCE_CONNECTION, edgeConfidence(edge.getStrength()), null, null, null),
                Evidence::strongest);
        }

        Map<Integer, List<IntroCandidatePerson>> byCompany = new HashMap<>();
        for (IntroCandidatePerson candidate : candidates) {
            if (candidate.getCompanyId() != null) {
                byCompany.computeIfAbsent(candidate.getCompanyId(), key -> new ArrayList<>()).add(candidate);
            }
        }
        for (List<IntroCandidatePerson> group : byCompany.values()) {
            if (group.size() < 2 || group.size() > MAX_COMPANY_FANOUT) {
                continue;
            }
            group.sort(Comparator.comparingInt(IntroCandidatePerson::getId));
            String company = groupCompanyName(group);
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    evidence.merge(pairKey(group.get(i).getId(), group.get(j).getId()),
                        new Evidence(EVIDENCE_COLLEAGUES, CONF_COLLEAGUES, company, null, null),
                        Evidence::strongest);
                }
            }
        }

        Map<String, Map<Integer, List<IntroEmploymentRow>>> byEmployer = new HashMap<>();
        for (IntroEmploymentRow stint : employment) {
            if (!candidateIds.contains(stint.getPersonId())) {
                continue;
            }
            String identity = employerIdentity(stint);
            if (identity == null) {
                continue;
            }
            List<IntroEmploymentRow> stints = byEmployer
                .computeIfAbsent(identity, key -> new HashMap<>())
                .computeIfAbsent(stint.getPersonId(), key -> new ArrayList<>());
            if (stints.size() < MAX_STINTS_PER_EMPLOYER) {
                stints.add(stint);
            }
        }
        for (Map<Integer, List<IntroEmploymentRow>> group : byEmployer.values()) {
            if (group.size() < 2 || group.size() > MAX_COMPANY_FANOUT) {
                continue;
            }
            List<Integer> people = new ArrayList<>(group.keySet());
            people.sort(Comparator.naturalOrder());
            for (int i = 0; i < people.size(); i++) {
                for (int j = i + 1; j < people.size(); j++) {
                    Evidence overlap = firstOverlap(group.get(people.get(i)), group.get(people.get(j)));
                    if (overlap != null) {
                        evidence.merge(pairKey(people.get(i), people.get(j)), overlap, Evidence::strongest);
                    }
                }
            }
        }
        return evidence;
    }

    /** The display name for a current-employer group: the first member with a non-blank name. */
    private static String groupCompanyName(List<IntroCandidatePerson> group) {
        for (IntroCandidatePerson member : group) {
            if (notBlank(member.getCompanyName())) {
                return member.getCompanyName().trim();
            }
        }
        return null;
    }

    /** The first overlapping stint pair between two people at a shared employer, or {@code null}. */
    private static Evidence firstOverlap(List<IntroEmploymentRow> stintsA, List<IntroEmploymentRow> stintsB) {
        for (IntroEmploymentRow a : stintsA) {
            for (IntroEmploymentRow b : stintsB) {
                if (!overlaps(a, b)) {
                    continue;
                }
                String company = notBlank(a.getCompanyName()) ? a.getCompanyName().trim()
                    : (notBlank(b.getCompanyName()) ? b.getCompanyName().trim() : null);
                return new Evidence(EVIDENCE_FORMER_COLLEAGUES, CONF_FORMER_COLLEAGUES, company,
                    overlapStartYear(a, b), overlapEndYear(a, b));
            }
        }
        return null;
    }

    /** Records a warm-path dismissal: per avenue with a bridge, or for every path to the target. */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public void dismissPath(int targetPersonId, Integer bridgePersonId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireOwnedPerson(workspaceId, targetPersonId);
        int userId = authService.getCurrentUser().getId();
        if (bridgePersonId == null) {
            introductionMapper.deleteWarmPathDismissals(workspaceId, targetPersonId);
            introductionMapper.recordWarmPathTargetDismissal(
                workspaceId, targetPersonId, STATUS_DISMISSED, userId);
            return;
        }
        if (bridgePersonId == targetPersonId) {
            throw new BadRequestException("A contact cannot bridge an introduction to itself");
        }
        requireOwnedPerson(workspaceId, bridgePersonId);
        introductionMapper.recordWarmPathDismissal(
            workspaceId, targetPersonId, bridgePersonId, STATUS_DISMISSED, userId);
    }

    /**
     * Accepts a warm path: creates the follow-up task asking the bridge for the introduction
     * (assigned to the acting user, linked to the target contact), records the accepted avenue,
     * and retires the whole target from the feed — the intro is now in flight, so the remaining
     * avenues would be noise. The caller may supply localized task text; the server composes a
     * default with mention tokens when it is absent. Requires {@code TASK_CREATE} in addition to
     * {@code PERSON_UPDATE}, enforced by the task service.
     */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public Task acceptPath(int targetPersonId, int bridgePersonId, String taskDescription) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (bridgePersonId == targetPersonId) {
            throw new BadRequestException("A contact cannot bridge an introduction to itself");
        }
        requireOwnedPerson(workspaceId, targetPersonId);
        requireOwnedPerson(workspaceId, bridgePersonId);
        User actor = authService.getCurrentUser();

        Task task = new Task();
        task.setDescription(resolveTaskDescription(workspaceId, targetPersonId, bridgePersonId, taskDescription));
        task.setAssignedTo(actor);
        Person target = new Person();
        target.setId(targetPersonId);
        task.setPerson(target);
        Task created = taskService.create(task);

        introductionMapper.recordWarmPathDismissal(
            workspaceId, targetPersonId, bridgePersonId, STATUS_ACCEPTED, actor.getId());
        introductionMapper.deleteWarmPathDismissals(workspaceId, targetPersonId);
        introductionMapper.recordWarmPathTargetDismissal(
            workspaceId, targetPersonId, STATUS_ACCEPTED, actor.getId());
        return created;
    }

    /**
     * The follow-up task text: the caller's localized copy when supplied (the frontend always
     * sends it, localized from its own locale cookie), otherwise a default built from mention
     * tokens so the task links both contacts. The default's language follows the request's
     * {@code Accept-Language} (the only locale signal API callers carry), which may differ from
     * the web app's cookie-selected locale.
     */
    private String resolveTaskDescription(
            int workspaceId, int targetPersonId, int bridgePersonId, String taskDescription) {
        if (taskDescription != null && !taskDescription.isBlank()) {
            return taskDescription.trim();
        }
        Person bridge = personMapper.getPersonById(workspaceId, bridgePersonId);
        Person target = personMapper.getPersonById(workspaceId, targetPersonId);
        String bridgeToken = mentionToken(bridge, bridgePersonId);
        String targetToken = mentionToken(target, targetPersonId);
        if ("ja".equals(LocaleContextHolder.getLocale().getLanguage())) {
            return bridgeToken + " に " + targetToken + " への紹介を依頼する";
        }
        return "Ask " + bridgeToken + " to introduce you to " + targetToken;
    }

    private static String mentionToken(Person person, int personId) {
        String label = person != null && notBlank(person.getName()) ? person.getName().trim() : "#" + personId;
        return "[" + label + "](person:" + personId + ")";
    }

    private void requireOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
    }

    private static boolean bridgeEligible(RelationshipTemperatureDto temperature) {
        if (temperature == null) {
            return false;
        }
        return BAND_HOT.equals(temperature.getBand()) || BAND_WARM.equals(temperature.getBand());
    }

    private static boolean targetEligible(RelationshipTemperatureDto temperature) {
        if (neverEngaged(temperature)) {
            return true;
        }
        boolean coldEnough = !BAND_HOT.equals(temperature.getBand())
            && !BAND_WARM.equals(temperature.getBand());
        return coldEnough && temperature.getDaysSinceTouch() >= MIN_DORMANT_DAYS;
    }

    /**
     * Whether the team has never touched this contact. Detected by the absence of a last touch —
     * NOT by {@code touchCount}, which only counts touches inside the scorer's recent window and
     * is 0 for any relationship that has merely gone quiet.
     */
    private static boolean neverEngaged(RelationshipTemperatureDto temperature) {
        return temperature == null || temperature.getDaysSinceTouch() == null;
    }

    private static double edgeConfidence(int strength) {
        if (strength >= 3) {
            return CONF_EDGE_STRONG;
        }
        return strength <= 1 ? CONF_EDGE_WEAK : CONF_EDGE_DEFAULT;
    }

    /**
     * Whether two stints overlap in time. MySQL datetime strings order lexicographically, so no
     * parsing is needed; a {@code null} end is a current employment (open-ended) and a
     * {@code null} start is treated as reaching arbitrarily far back. The comparison is strict:
     * one stint starting the instant the other ends is a handoff, not an overlap.
     */
    private static boolean overlaps(IntroEmploymentRow a, IntroEmploymentRow b) {
        return startsBeforeEnd(a.getStartedAt(), b.getEndedAt())
            && startsBeforeEnd(b.getStartedAt(), a.getEndedAt());
    }

    private static boolean startsBeforeEnd(String start, String end) {
        return start == null || end == null || start.compareTo(end) < 0;
    }

    private static Integer overlapStartYear(IntroEmploymentRow a, IntroEmploymentRow b) {
        String later = maxOf(a.getStartedAt(), b.getStartedAt());
        return year(later);
    }

    private static Integer overlapEndYear(IntroEmploymentRow a, IntroEmploymentRow b) {
        if (a.getEndedAt() == null && b.getEndedAt() == null) {
            return null;
        }
        String earlier;
        if (a.getEndedAt() == null) {
            earlier = b.getEndedAt();
        } else if (b.getEndedAt() == null) {
            earlier = a.getEndedAt();
        } else {
            earlier = a.getEndedAt().compareTo(b.getEndedAt()) <= 0 ? a.getEndedAt() : b.getEndedAt();
        }
        return year(earlier);
    }

    private static String maxOf(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Integer year(String mysqlDateTime) {
        if (mysqlDateTime == null || mysqlDateTime.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(mysqlDateTime.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String employerIdentity(IntroEmploymentRow row) {
        if (row.getCompanyId() != null) {
            return "id:" + row.getCompanyId();
        }
        if (notBlank(row.getCompanyName())) {
            return "name:" + row.getCompanyName().trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static int score(Map<Integer, RelationshipTemperatureDto> temperatures, int id) {
        RelationshipTemperatureDto temperature = temperatures.get(id);
        return temperature == null ? 0 : temperature.getScore();
    }

    private static String band(Map<Integer, RelationshipTemperatureDto> temperatures, int id) {
        RelationshipTemperatureDto temperature = temperatures.get(id);
        return temperature == null ? COLD_BAND : temperature.getBand();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static long pairKey(int x, int y) {
        int lower = Math.min(x, y);
        int higher = Math.max(x, y);
        return ((long) lower << 32) | (higher & 0xffffffffL);
    }

    private static long directedKey(int bridge, int target) {
        return ((long) bridge << 32) | (target & 0xffffffffL);
    }

    /** One tier of bridge-to-target evidence; {@code strongest} keeps the higher-confidence tier. */
    record Evidence(String type, double confidence, String company,
                    Integer overlapStartYear, Integer overlapEndYear) {
        static Evidence strongest(Evidence a, Evidence b) {
            return b.confidence > a.confidence ? b : a;
        }
    }
}
