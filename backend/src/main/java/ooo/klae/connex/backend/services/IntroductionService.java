package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The "give side" of the relationship graph (issue #43): surfaces pairs of contacts the team is
 * positioned to introduce — because it knows both but they are not yet connected — and records the
 * introductions it makes so that lineage compounds over time.
 *
 * <p>Suggestions are ranked deterministically from graph signals only: shared employers, mutual
 * connections, and how warm each side's relationship is. Recording an introduction also creates the
 * resulting {@code person_edge}, so the new connection strengthens both relationships, shows on the
 * map, and is never suggested again. Every read and write is workspace-scoped.
 */
@Service
@RequiredArgsConstructor
public class IntroductionService {
    private final IntroductionMapper introductionMapper;
    private final PersonEdgeMapper edgeMapper;
    private final PersonMapper personMapper;
    private final ScoringService scoringService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;

    /** Mutual-connection count at which that signal is maxed out. */
    private static final int MUTUAL_CAP = 5;
    /** A contact connected to more candidates than this is treated as a hub and skipped for the
     *  mutual-connection signal, bounding the pairwise work and dropping low-value mass pairings. */
    private static final int MAX_HUB_FANOUT = 100;
    /** Companies with more engaged contacts than this are too large for "shared employer" to be a
     *  meaningful intro reason; their pairs are skipped (also bounds the pairwise work). */
    private static final int MAX_COMPANY_FANOUT = 40;
    private static final double WEIGHT_MUTUAL = 0.45;
    private static final double WEIGHT_SHARED = 0.30;
    private static final double WEIGHT_WARMTH = 0.25;

    private static final int DEFAULT_SUGGESTION_LIMIT = 20;
    private static final int MAX_SUGGESTION_LIMIT = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String COLD_BAND = "cold";
    private static final String DEFAULT_EDGE_TYPE = "knows";
    private static final int DEFAULT_EDGE_STRENGTH = 2;

    static final String REASON_MUTUAL = "mutual_connections";
    static final String REASON_SHARED_COMPANY = "shared_company";

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Ranked reverse-introduction suggestions for the active workspace. */
    public List<IntroSuggestionDto> getSuggestions(int limit) {
        int resolved = limit <= 0 ? DEFAULT_SUGGESTION_LIMIT : Math.min(limit, MAX_SUGGESTION_LIMIT);
        return computeSuggestions(workspaceService.getCurrentWorkspaceId(), resolved);
    }

    /**
     * Ranked reverse-introduction suggestions for the given workspace. Takes the workspace id
     * explicitly so it can run off the request thread (the scheduled notification sweep), where no
     * tenant context is resolved.
     */
    public List<IntroSuggestionDto> computeSuggestions(int workspaceId, int limit) {
        return computeSuggestions(workspaceId, limit, null);
    }

    /**
     * As {@link #computeSuggestions(int, int)}, but reuses an already-computed warmth map when the
     * caller has one (the scheduled sweep scores the workspace once for relationship nudges and
     * shares it here, avoiding a second full rescore). A {@code null} map is scored on demand.
     */
    public List<IntroSuggestionDto> computeSuggestions(
            int workspaceId, int limit, Map<Integer, RelationshipTemperatureDto> temperatures) {
        if (limit <= 0) {
            return List.of();
        }
        List<IntroCandidatePerson> candidates = introductionMapper.findCandidatePersons(workspaceId);
        if (candidates.size() < 2) {
            return List.of();
        }
        List<PersonEdge> edges = edgeMapper.getAllEdges(workspaceId);
        List<IntroEmploymentRow> employment = introductionMapper.findWorkspaceEmployment(workspaceId);
        Set<Long> existing = new HashSet<>();
        for (Introduction pair : introductionMapper.findExistingPairs(workspaceId)) {
            existing.add(pairKey(pair.getPersonAId(), pair.getPersonBId()));
        }
        Map<Integer, RelationshipTemperatureDto> warmth = temperatures;
        if (warmth == null) {
            warmth = new HashMap<>();
            for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
                warmth.put(temperature.getId(), temperature);
            }
        }
        return rankSuggestions(candidates, edges, employment, existing, warmth, limit);
    }

    /**
     * Pure ranking over already-loaded workspace data, so it can be unit-tested without a database.
     * Generates only pairs that share a structural signal (a mutual connection or an employer),
     * drops pairs that are already connected or already recorded, scores each, and returns the top
     * {@code limit} by descending score.
     */
    static List<IntroSuggestionDto> rankSuggestions(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment,
            Set<Long> existingPairs,
            Map<Integer, RelationshipTemperatureDto> temperatures,
            int limit) {
        if (limit <= 0 || candidates.size() < 2) {
            return List.of();
        }
        Map<Integer, IntroCandidatePerson> byId = new HashMap<>();
        for (IntroCandidatePerson candidate : candidates) {
            byId.put(candidate.getId(), candidate);
        }
        Set<Integer> candidateIds = byId.keySet();

        Map<Integer, Set<Integer>> neighbors = new HashMap<>();
        Set<Long> connected = new HashSet<>();
        for (PersonEdge edge : edges) {
            int source = edge.getSourcePersonId();
            int target = edge.getTargetPersonId();
            neighbors.computeIfAbsent(source, key -> new HashSet<>()).add(target);
            neighbors.computeIfAbsent(target, key -> new HashSet<>()).add(source);
            connected.add(pairKey(source, target));
        }

        Map<Long, Integer> mutual = countMutualConnections(neighbors, candidateIds);
        Map<Long, String> sharedCompany = sharedCompanyPairs(candidates, employment, candidateIds);

        Set<Long> pairKeys = new HashSet<>(mutual.keySet());
        pairKeys.addAll(sharedCompany.keySet());

        List<IntroSuggestionDto> suggestions = new ArrayList<>();
        for (Long key : pairKeys) {
            if (connected.contains(key) || existingPairs.contains(key)) {
                continue;
            }
            int personA = (int) (key >> 32);
            int personB = (int) (key & 0xffffffffL);
            IntroCandidatePerson partyA = byId.get(personA);
            IntroCandidatePerson partyB = byId.get(personB);
            if (partyA == null || partyB == null) {
                continue;
            }
            int mutualCount = mutual.getOrDefault(key, 0);
            String shared = sharedCompany.get(key);
            boolean hasShared = shared != null;
            if (mutualCount <= 0 && !hasShared) {
                continue;
            }
            suggestions.add(suggestion(partyA, partyB, mutualCount, shared, hasShared, temperatures));
        }

        suggestions.sort(Comparator
            .comparingInt(IntroSuggestionDto::getScore).reversed()
            .thenComparing(Comparator.comparingInt(IntroSuggestionDto::getMutualConnections).reversed())
            .thenComparingInt(IntroSuggestionDto::getPersonAId)
            .thenComparingInt(IntroSuggestionDto::getPersonBId));

        return suggestions.size() > limit
            ? new ArrayList<>(suggestions.subList(0, limit))
            : suggestions;
    }

    private static Map<Long, Integer> countMutualConnections(
            Map<Integer, Set<Integer>> neighbors, Set<Integer> candidateIds) {
        Map<Long, Integer> mutual = new HashMap<>();
        for (Set<Integer> connections : neighbors.values()) {
            List<Integer> shared = new ArrayList<>();
            for (Integer neighbor : connections) {
                if (candidateIds.contains(neighbor)) {
                    shared.add(neighbor);
                }
            }
            if (shared.size() < 2 || shared.size() > MAX_HUB_FANOUT) {
                continue;
            }
            for (int i = 0; i < shared.size(); i++) {
                for (int j = i + 1; j < shared.size(); j++) {
                    mutual.merge(pairKey(shared.get(i), shared.get(j)), 1, Integer::sum);
                }
            }
        }
        return mutual;
    }

    private static Map<Long, String> sharedCompanyPairs(
            List<IntroCandidatePerson> candidates,
            List<IntroEmploymentRow> employment,
            Set<Integer> candidateIds) {
        Map<String, Set<Integer>> members = new HashMap<>();
        Map<String, String> displayName = new HashMap<>();
        for (IntroCandidatePerson candidate : candidates) {
            if (candidate.getCompanyId() != null) {
                String identity = "id:" + candidate.getCompanyId();
                members.computeIfAbsent(identity, key -> new HashSet<>()).add(candidate.getId());
                if (notBlank(candidate.getCompanyName())) {
                    displayName.putIfAbsent(identity, candidate.getCompanyName().trim());
                }
            }
        }
        for (IntroEmploymentRow row : employment) {
            if (!candidateIds.contains(row.getPersonId())) {
                continue;
            }
            String identity = employerIdentity(row);
            if (identity == null) {
                continue;
            }
            members.computeIfAbsent(identity, key -> new HashSet<>()).add(row.getPersonId());
            if (notBlank(row.getCompanyName())) {
                displayName.putIfAbsent(identity, row.getCompanyName().trim());
            }
        }

        Map<Long, String> pairs = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : members.entrySet()) {
            Set<Integer> group = entry.getValue();
            if (group.size() < 2 || group.size() > MAX_COMPANY_FANOUT) {
                continue;
            }
            List<Integer> ordered = new ArrayList<>(group);
            ordered.sort(Comparator.naturalOrder());
            String name = displayName.getOrDefault(entry.getKey(), "");
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    pairs.putIfAbsent(pairKey(ordered.get(i), ordered.get(j)), name);
                }
            }
        }
        return pairs;
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

    private static IntroSuggestionDto suggestion(
            IntroCandidatePerson partyA,
            IntroCandidatePerson partyB,
            int mutualCount,
            String shared,
            boolean hasShared,
            Map<Integer, RelationshipTemperatureDto> temperatures) {
        int scoreA = score(temperatures, partyA.getId());
        int scoreB = score(temperatures, partyB.getId());
        double mutualComponent = Math.min(mutualCount, MUTUAL_CAP) / (double) MUTUAL_CAP;
        double sharedComponent = hasShared ? 1.0 : 0.0;
        double warmthComponent = (scoreA + scoreB) / 200.0;
        double raw = WEIGHT_MUTUAL * mutualComponent + WEIGHT_SHARED * sharedComponent
            + WEIGHT_WARMTH * warmthComponent;

        List<String> reasons = new ArrayList<>();
        if (mutualCount > 0) {
            reasons.add(REASON_MUTUAL);
        }
        if (hasShared) {
            reasons.add(REASON_SHARED_COMPANY);
        }

        IntroSuggestionDto dto = new IntroSuggestionDto();
        dto.setPersonAId(partyA.getId());
        dto.setPersonAName(partyA.getName());
        dto.setPersonATitle(partyA.getTitle());
        dto.setPersonACompany(partyA.getCompanyName());
        dto.setPersonAImageUrl(partyA.getImageUrl());
        dto.setPersonAWarmth(band(temperatures, partyA.getId()));
        dto.setPersonBId(partyB.getId());
        dto.setPersonBName(partyB.getName());
        dto.setPersonBTitle(partyB.getTitle());
        dto.setPersonBCompany(partyB.getCompanyName());
        dto.setPersonBImageUrl(partyB.getImageUrl());
        dto.setPersonBWarmth(band(temperatures, partyB.getId()));
        dto.setScore((int) Math.round(100.0 * raw));
        dto.setReasons(reasons);
        dto.setMutualConnections(mutualCount);
        dto.setSharedCompany(hasShared && notBlank(shared) ? shared : null);
        return dto;
    }

    /** Records an introduction the team made between two of its contacts, connecting them. */
    @Transactional
    @RequirePermission(Permission.PERSON_UPDATE)
    public IntroductionDto createIntroduction(int personAId, int personBId, String note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (personAId == personBId) {
            throw new BadRequestException("A contact cannot be introduced to itself");
        }
        requireOwnedPerson(workspaceId, personAId);
        requireOwnedPerson(workspaceId, personBId);
        int lower = Math.min(personAId, personBId);
        int higher = Math.max(personAId, personBId);

        Introduction introduction = new Introduction();
        introduction.setWorkspaceId(workspaceId);
        introduction.setIntroducerUserId(authService.getCurrentUser().getId());
        introduction.setPersonAId(lower);
        introduction.setPersonBId(higher);
        introduction.setNote(trimToNull(note));
        introduction.setIntroducedAt(now());
        introductionMapper.recordMade(introduction);

        PersonEdge edge = new PersonEdge();
        edge.setWorkspaceId(workspaceId);
        edge.setSourcePersonId(lower);
        edge.setTargetPersonId(higher);
        edge.setType(DEFAULT_EDGE_TYPE);
        edge.setStrength(DEFAULT_EDGE_STRENGTH);
        edgeMapper.insertIfAbsent(edge);

        return introductionMapper.findByPair(workspaceId, lower, higher);
    }

    /** Dismisses a suggested pair so it stops being surfaced; never undoes a recorded introduction. */
    @RequirePermission(Permission.PERSON_UPDATE)
    public void dismissSuggestion(int personAId, int personBId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (personAId == personBId) {
            throw new BadRequestException("A contact cannot be introduced to itself");
        }
        requireOwnedPerson(workspaceId, personAId);
        requireOwnedPerson(workspaceId, personBId);

        Introduction introduction = new Introduction();
        introduction.setWorkspaceId(workspaceId);
        introduction.setIntroducerUserId(authService.getCurrentUser().getId());
        introduction.setPersonAId(Math.min(personAId, personBId));
        introduction.setPersonBId(Math.max(personAId, personBId));
        introduction.setIntroducedAt(now());
        introductionMapper.recordDismissed(introduction);
    }

    /** The lineage feed: introductions the team has made, newest first. */
    public PageResponse<IntroductionDto> getLineage(int page, int size) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int limit = size <= 0 ? DEFAULT_SUGGESTION_LIMIT : Math.min(size, MAX_PAGE_SIZE);
        int offset = (Math.max(1, page) - 1) * limit;
        List<IntroductionDto> items = introductionMapper.findLineage(workspaceId, limit, offset);
        long total = introductionMapper.countLineage(workspaceId);
        return new PageResponse<>(items, total);
    }

    private void requireOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) {
            throw new ResourceNotFoundException("Person not found with id: " + personId);
        }
    }

    private String now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(MYSQL_DATETIME);
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long pairKey(int x, int y) {
        int lower = Math.min(x, y);
        int higher = Math.max(x, y);
        return ((long) lower << 32) | (higher & 0xffffffffL);
    }
}
