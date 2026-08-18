package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WarmPathDismissal;
import ooo.klae.connex.backend.dto.IntroOverviewDto;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.ReferenceDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.dto.WarmPathDto;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * The "give side" of the relationship graph (issue #43): surfaces pairs of contacts the team is
 * positioned to introduce — because it knows both but they are not yet connected — and records the
 * introductions it makes so that lineage compounds over time.
 *
 * <p>Suggestions are ranked deterministically from graph signals only: shared employers, mutual
 * connections, and how warm each side's relationship is. Recording an introduction also creates the
 * resulting {@code person_edge}, so the new connection strengthens both relationships, shows on the
 * map, and is never suggested again. Tenant data stays workspace-scoped; introducer labels are
 * resolved separately from the control plane using ids stored on the workspace-owned lineage.
 */
@Service
@RequiredArgsConstructor
public class IntroductionService {
    private final IntroductionMapper introductionMapper;
    private final UserMapper userMapper;
    private final PersonEdgeMapper edgeMapper;
    private final PersonEdgeReadService edgeReader;
    private final PersonMapper personMapper;
    private final ScoringService scoringService;
    private final WarmPathService warmPathService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final Clock clock;
    private final ReferenceService referenceService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final ObjectMapper objectMapper;
    private final TenantWorkScope tenantWorkScope;

    private static final String MENTION_TYPE = "introduction.mention";
    private static final String MENTION_CATEGORY = "introduction";
    private static final String MENTION_SEVERITY = "info";
    private static final String IN_APP = "in_app";
    private static final int SNIPPET_LENGTH = 140;

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
    /** Score multiplier applied to a pair already at the same current employer: they are presumed
     *  to know each other, so such intros are down-ranked far below cross-company ones — but kept,
     *  not dropped, so the user can still act on them and the lineage signal keeps accruing. */
    private static final double SAME_EMPLOYER_PENALTY = 0.5;

    private static final int DEFAULT_SUGGESTION_LIMIT = 20;
    private static final int MAX_SUGGESTION_LIMIT = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String COLD_BAND = "cold";
    private static final String DEFAULT_EDGE_TYPE = "knows";
    private static final int DEFAULT_EDGE_STRENGTH = 2;

    static final String REASON_MUTUAL = "mutual_connections";
    static final String REASON_SHARED_COMPANY = "shared_company";
    static final String EMPTY_INSUFFICIENT_CANDIDATES = "insufficient_candidates";
    static final String EMPTY_MISSING_RELATIONSHIP_EVIDENCE = "missing_relationship_evidence";
    static final String EMPTY_POLICY_EXCLUSION = "policy_exclusion";
    static final String EMPTY_INSUFFICIENT_PATH_STRENGTH = "insufficient_path_strength";

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Ranked reverse-introduction suggestions for the active workspace. */
    public List<IntroSuggestionDto> getSuggestions(int limit) {
        int resolved = limit <= 0 ? DEFAULT_SUGGESTION_LIMIT : Math.min(limit, MAX_SUGGESTION_LIMIT);
        return computeSuggestions(workspaceService.getCurrentWorkspaceId(), resolved);
    }

    /**
     * The introductions page's combined feed — give-side suggestions and receive-side warm paths
     * (issue #630) — computed from a single workspace warmth pass instead of one full rescore per
     * section.
     */
    public IntroOverviewDto getOverview(int suggestionLimit, int pathLimit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Instant asOf = clock.instant();
        int resolvedSuggestions = suggestionLimit <= 0
            ? DEFAULT_SUGGESTION_LIMIT
            : Math.min(suggestionLimit, MAX_SUGGESTION_LIMIT);
        Map<Integer, RelationshipTemperatureDto> warmth = new HashMap<>();
        for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
            warmth.put(temperature.getId(), temperature);
        }
        Set<Integer> excludedPersonIds =
            new HashSet<>(introductionMapper.findIntroExcludedPersonIds(workspaceId));
        List<PersonEdge> allEdges = edgeReader.getAllEdges(workspaceId);
        List<PersonEdge> edges = eligibleEdges(allEdges, excludedPersonIds);
        List<IntroEmploymentRow> employment = introductionMapper.findWorkspaceEmployment(workspaceId);
        List<IntroCandidatePerson> suggestionCandidates =
            introductionMapper.findCandidatePersons(workspaceId);
        Set<Long> existingPairs =
            existingPairKeys(introductionMapper.findExistingPairs(workspaceId));
        List<IntroSuggestionDto> suggestions = rankSuggestions(
            suggestionCandidates, edges, employment, existingPairs, warmth, resolvedSuggestions);
        List<IntroCandidatePerson> pathCandidates =
            introductionMapper.findWarmPathCandidates(workspaceId);
        List<WarmPathDismissal> pathDismissals =
            introductionMapper.findWarmPathDismissals(workspaceId);
        List<WarmPathDto> paths = WarmPathService.rankPaths(
            pathCandidates,
            edges,
            employment,
            pathDismissals,
            warmth,
            null,
            WarmPathService.resolveLimit(pathLimit));
        String timestamp = asOf.toString();
        suggestions.forEach(suggestion -> suggestion.setAsOf(timestamp));
        paths.forEach(path -> path.setAsOf(timestamp));
        return new IntroOverviewDto(
            suggestions,
            paths,
            timestamp,
            suggestions.isEmpty()
                ? diagnoseSuggestionsEmpty(
                    workspaceId,
                    suggestionCandidates,
                    edges,
                    allEdges,
                    employment,
                    existingPairs,
                    warmth)
                : null,
            paths.isEmpty()
                ? warmPathService.diagnoseEmpty(
                    workspaceId, pathCandidates, edges, employment, pathDismissals, warmth)
                : null);
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
        Set<Integer> excludedPersonIds =
            new HashSet<>(introductionMapper.findIntroExcludedPersonIds(workspaceId));
        List<PersonEdge> edges = eligibleEdges(edgeReader.getAllEdges(workspaceId), excludedPersonIds);
        List<IntroEmploymentRow> employment = introductionMapper.findWorkspaceEmployment(workspaceId);
        Set<Long> existing = existingPairKeys(introductionMapper.findExistingPairs(workspaceId));
        Map<Integer, RelationshipTemperatureDto> warmth = temperatures;
        if (warmth == null) {
            warmth = new HashMap<>();
            for (RelationshipTemperatureDto temperature : scoringService.scoreContacts(workspaceId)) {
                warmth.put(temperature.getId(), temperature);
            }
        }
        List<IntroSuggestionDto> suggestions =
            rankSuggestions(candidates, edges, employment, existing, warmth, limit);
        String asOf = clock.instant().toString();
        suggestions.forEach(suggestion -> suggestion.setAsOf(asOf));
        return suggestions;
    }

    /**
     * Pure ranking over already-loaded workspace data, so it can be unit-tested without a database.
     * Generates only pairs that share a structural signal (a mutual connection or an employer),
     * drops pairs that are already connected or already recorded, scores each, and returns the top
     * {@code limit} by descending score.
     *
     * <p>Contacts who currently work at the same company are heavily de-prioritized rather than
     * dropped: same-employer colleagues are likely to already know each other, so simply sharing a
     * current employer is not a reason to introduce them, and such a pair only surfaces when a
     * mutual connection makes the intro non-obvious — then ranked well below cross-company pairs via
     * {@link #SAME_EMPLOYER_PENALTY}. Keeping them surfaceable means the user can still make the
     * intro and the made/dismissed signal keeps building. The shared-employer reason itself only
     * rewards a <em>former</em> or cross-time overlap (a past employer, or one contact at a company
     * the other has since left) — the reconnection that actually has value.
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

        Map<Integer, Map<Integer, Integer>> neighborEdges = new HashMap<>();
        Set<Long> connected = new HashSet<>();
        for (PersonEdge edge : edges) {
            int source = edge.getSourcePersonId();
            int target = edge.getTargetPersonId();
            neighborEdges.computeIfAbsent(source, key -> new HashMap<>()).put(target, edge.getId());
            neighborEdges.computeIfAbsent(target, key -> new HashMap<>()).put(source, edge.getId());
            connected.add(pairKey(source, target));
        }

        Map<Long, MutualEvidence> mutual = countMutualConnections(neighborEdges, candidateIds);
        Map<Long, CompanyEvidence> sharedCompany = sharedCompanyPairs(candidates, employment, candidateIds);

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
            MutualEvidence mutualEvidence = mutual.get(key);
            int mutualCount = mutualEvidence == null ? 0 : mutualEvidence.connectorIds().size();
            boolean sameCurrent = sameCurrentEmployer(partyA, partyB);
            CompanyEvidence shared = sameCurrent ? null : sharedCompany.get(key);
            boolean hasShared = shared != null;
            boolean surfaced = sameCurrent ? mutualCount > 0 : (mutualCount > 0 || hasShared);
            if (!surfaced) {
                continue;
            }
            suggestions.add(suggestion(
                partyA, partyB, mutualEvidence, shared, hasShared, sameCurrent, temperatures));
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

    private static Map<Long, MutualEvidence> countMutualConnections(
            Map<Integer, Map<Integer, Integer>> neighbors, Set<Integer> candidateIds) {
        Map<Long, MutualEvidence> mutual = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : neighbors.entrySet()) {
            List<Integer> shared = new ArrayList<>();
            for (Integer neighbor : entry.getValue().keySet()) {
                if (candidateIds.contains(neighbor)) {
                    shared.add(neighbor);
                }
            }
            if (shared.size() < 2 || shared.size() > MAX_HUB_FANOUT) {
                continue;
            }
            for (int i = 0; i < shared.size(); i++) {
                for (int j = i + 1; j < shared.size(); j++) {
                    MutualEvidence evidence = mutual.computeIfAbsent(
                        pairKey(shared.get(i), shared.get(j)),
                        key -> new MutualEvidence(new HashSet<>(), new HashSet<>()));
                    evidence.connectorIds().add(entry.getKey());
                    int firstEdgeId = entry.getValue().get(shared.get(i));
                    int secondEdgeId = entry.getValue().get(shared.get(j));
                    if (firstEdgeId > 0) {
                        evidence.edgeIds().add(firstEdgeId);
                    }
                    if (secondEdgeId > 0) {
                        evidence.edgeIds().add(secondEdgeId);
                    }
                }
            }
        }
        return mutual;
    }

    private static Map<Long, CompanyEvidence> sharedCompanyPairs(
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

        Map<Long, CompanyEvidence> pairs = new HashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : members.entrySet()) {
            Set<Integer> group = entry.getValue();
            if (group.size() < 2 || group.size() > MAX_COMPANY_FANOUT) {
                continue;
            }
            List<Integer> ordered = new ArrayList<>(group);
            ordered.sort(Comparator.naturalOrder());
            String name = displayName.getOrDefault(entry.getKey(), "");
            CompanyEvidence evidence = new CompanyEvidence(name);
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    pairs.putIfAbsent(pairKey(ordered.get(i), ordered.get(j)), evidence);
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
            MutualEvidence mutualEvidence,
            CompanyEvidence shared,
            boolean hasShared,
            boolean sameCurrentEmployer,
            Map<Integer, RelationshipTemperatureDto> temperatures) {
        int mutualCount = mutualEvidence == null ? 0 : mutualEvidence.connectorIds().size();
        int scoreA = score(temperatures, partyA.getId());
        int scoreB = score(temperatures, partyB.getId());
        double mutualComponent = Math.min(mutualCount, MUTUAL_CAP) / (double) MUTUAL_CAP;
        double sharedComponent = hasShared ? 1.0 : 0.0;
        double warmthComponent = (scoreA + scoreB) / 200.0;
        double raw = WEIGHT_MUTUAL * mutualComponent + WEIGHT_SHARED * sharedComponent
            + WEIGHT_WARMTH * warmthComponent;
        if (sameCurrentEmployer) {
            raw *= SAME_EMPLOYER_PENALTY;
        }

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
        dto.setSharedCompany(hasShared && notBlank(shared.name()) ? shared.name() : null);
        dto.setSupportingPersonIds(mutualEvidence == null
            ? List.of()
            : mutualEvidence.connectorIds().stream().sorted().toList());
        dto.setSupportingEdgeIds(mutualEvidence == null
            ? List.of()
            : mutualEvidence.edgeIds().stream().sorted().toList());
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
        User actor = authService.getCurrentUser();
        int lower = Math.min(personAId, personBId);
        int higher = Math.max(personAId, personBId);

        Introduction introduction = new Introduction();
        introduction.setWorkspaceId(workspaceId);
        introduction.setIntroducerUserId(actor.getId());
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

        IntroductionDto dto = introductionMapper.findByPair(workspaceId, lower, higher);
        dto.setIntroducerName(actor.getDisplayName());
        List<Integer> mentioned =
            referenceService.syncReferences(workspaceId, ReferenceService.SOURCE_INTRODUCTION, dto.getId(), dto.getNote());
        notifyMentions(workspaceId, dto, mentioned, actor);
        dto.setReferences(referenceService
            .referencesFor(workspaceId, ReferenceService.SOURCE_INTRODUCTION, dto.getId())
            .stream().map(ReferenceDto::from).toList());
        return dto;
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
        hydrateIntroducerNames(items);
        hydrateReferences(workspaceId, items);
        long total = introductionMapper.countLineage(workspaceId);
        return new PageResponse<>(items, total);
    }

    private void hydrateIntroducerNames(List<IntroductionDto> items) {
        List<Integer> introducerIds = items.stream()
            .map(IntroductionDto::getIntroducerId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (introducerIds.isEmpty()) {
            return;
        }
        Map<Integer, String> namesById = new HashMap<>();
        for (UserDisplayNameDto user :
                tenantWorkScope.unrouted(() -> userMapper.getDisplayNamesByIds(introducerIds))) {
            namesById.put(user.id(), user.displayName());
        }
        for (IntroductionDto item : items) {
            item.setIntroducerName(namesById.get(item.getIntroducerId()));
        }
    }

    private void hydrateReferences(int workspaceId, List<IntroductionDto> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<Integer, List<EntityReference>> bySource = referenceService.referencesBySource(
            workspaceId, ReferenceService.SOURCE_INTRODUCTION, items.stream().map(IntroductionDto::getId).toList());
        List<ReferenceService.ReaderVisibleContent> visible = referenceService.redactInvisibleNoteTargets(
            workspaceId,
            items.stream()
                .map(item -> new ReferenceService.ReaderVisibleContent(
                    item.getNote(), bySource.getOrDefault(item.getId(), List.of())))
                .toList());
        for (int index = 0; index < items.size(); index++) {
            IntroductionDto item = items.get(index);
            ReferenceService.ReaderVisibleContent content = visible.get(index);
            item.setNote(content.content());
            item.setReferences(content.references()
                .stream().map(ReferenceDto::from).toList());
        }
    }

    private void notifyMentions(int workspaceId, IntroductionDto dto, List<Integer> recipientIds, User actor) {
        if (recipientIds.isEmpty()) {
            return;
        }
        String snippet = snippet(dto.getNote());
        String triggeredAt = now();
        String actionUrl = "/overview/introductions";
        for (int recipientId : recipientIds) {
            if (recipientId == actor.getId()) {
                continue;
            }
            if (!notificationPreferenceService.isEnabled(recipientId, MENTION_TYPE, IN_APP)) {
                continue;
            }
            try {
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(MENTION_TYPE);
                notification.setCategory(MENTION_CATEGORY);
                notification.setSeverity(MENTION_SEVERITY);
                notification.setTemplateVersion(1);
                notification.setTitle("New mention");
                notification.setBody(actor.getDisplayName() + " mentioned you in an introduction");
                notification.setActorId(actor.getId());
                notification.setActorLabel(actor.getDisplayName());
                notification.setSourceType("introduction");
                notification.setSourceId(dto.getId());
                notification.setSourceLabel(snippet);
                notification.setActionUrl(actionUrl);
                notification.setDedupeKey(MENTION_TYPE + ":" + dto.getId() + ":" + recipientId);
                notification.setTriggeredAt(triggeredAt);
                notification.setData(json(Map.of("introductionId", dto.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static String snippet(String content) {
        String plain = ReferenceService.toPlainText(content).strip();
        return plain.length() > SNIPPET_LENGTH ? plain.substring(0, SNIPPET_LENGTH) : plain;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize notification data", exception);
        }
    }

    private void requireOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) {
            throw new ResourceNotFoundException("Contact not found");
        }
    }

    private String now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).format(MYSQL_DATETIME);
    }

    private String diagnoseSuggestionsEmpty(
            int workspaceId,
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<PersonEdge> allEdges,
            List<IntroEmploymentRow> employment,
            Set<Long> existingPairs,
            Map<Integer, RelationshipTemperatureDto> temperatures) {
        if (candidates.size() < 2) {
            return candidates.size() + introductionMapper.countExcludedCandidatePersons(workspaceId) >= 2
                ? EMPTY_POLICY_EXCLUSION
                : EMPTY_INSUFFICIENT_CANDIDATES;
        }
        List<IntroSuggestionDto> beforePairPolicy =
            rankSuggestions(candidates, edges, employment, Set.of(), temperatures, Integer.MAX_VALUE);
        if (!beforePairPolicy.isEmpty() && beforePairPolicy.stream().allMatch(suggestion ->
                existingPairs.contains(pairKey(suggestion.getPersonAId(), suggestion.getPersonBId())))) {
            return EMPTY_POLICY_EXCLUSION;
        }
        if (beforePairPolicy.isEmpty() && allEdges.size() != edges.size()) {
            List<IntroSuggestionDto> includingRestrictedConnectors = rankSuggestions(
                candidates, allEdges, employment, Set.of(), temperatures, Integer.MAX_VALUE);
            if (!includingRestrictedConnectors.isEmpty()) {
                return EMPTY_POLICY_EXCLUSION;
            }
        }
        if (!hasPotentialSuggestionEvidence(candidates, edges, employment)) {
            return EMPTY_MISSING_RELATIONSHIP_EVIDENCE;
        }
        return EMPTY_INSUFFICIENT_PATH_STRENGTH;
    }

    private static boolean hasPotentialSuggestionEvidence(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment) {
        Set<Integer> candidateIds = candidates.stream()
            .map(IntroCandidatePerson::getId)
            .collect(Collectors.toSet());
        Map<Integer, Integer> candidateNeighbors = new HashMap<>();
        for (PersonEdge edge : edges) {
            if (candidateIds.contains(edge.getSourcePersonId())) {
                candidateNeighbors.merge(edge.getTargetPersonId(), 1, Integer::sum);
            }
            if (candidateIds.contains(edge.getTargetPersonId())) {
                candidateNeighbors.merge(edge.getSourcePersonId(), 1, Integer::sum);
            }
        }
        if (candidateNeighbors.values().stream().anyMatch(count -> count >= 2)) {
            return true;
        }
        Map<String, Set<Integer>> byEmployer = new HashMap<>();
        for (IntroCandidatePerson candidate : candidates) {
            if (candidate.getCompanyId() != null) {
                byEmployer.computeIfAbsent(
                    "id:" + candidate.getCompanyId(), key -> new HashSet<>()).add(candidate.getId());
            }
        }
        for (IntroEmploymentRow row : employment) {
            if (!candidateIds.contains(row.getPersonId())) {
                continue;
            }
            String identity = employerIdentity(row);
            if (identity != null) {
                byEmployer.computeIfAbsent(identity, key -> new HashSet<>()).add(row.getPersonId());
            }
        }
        return byEmployer.values().stream().anyMatch(people -> people.size() >= 2);
    }

    private static List<PersonEdge> eligibleEdges(
            List<PersonEdge> edges, Set<Integer> excludedPersonIds) {
        if (excludedPersonIds.isEmpty()) {
            return edges;
        }
        return edges.stream()
            .filter(edge -> !excludedPersonIds.contains(edge.getSourcePersonId())
                && !excludedPersonIds.contains(edge.getTargetPersonId()))
            .toList();
    }

    private static Set<Long> existingPairKeys(List<Introduction> pairs) {
        Set<Long> existing = new HashSet<>();
        for (Introduction pair : pairs) {
            existing.add(pairKey(pair.getPersonAId(), pair.getPersonBId()));
        }
        return existing;
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

    /** True when both contacts list the same non-null current employer (likely already colleagues). */
    private static boolean sameCurrentEmployer(IntroCandidatePerson a, IntroCandidatePerson b) {
        return a.getCompanyId() != null && a.getCompanyId().equals(b.getCompanyId());
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

    private record MutualEvidence(Set<Integer> connectorIds, Set<Integer> edgeIds) {
    }

    private record CompanyEvidence(String name) {
    }
}
