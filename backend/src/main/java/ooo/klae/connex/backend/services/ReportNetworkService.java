package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.Introduction;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportNetworkAccountRow;
import ooo.klae.connex.backend.dto.ReportWidgetConfig;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;

/** Computes deterministic, current-state network figures for report widgets. */
@Service
@RequiredArgsConstructor
public class ReportNetworkService {
    static final int MAX_PATH_HOPS = 3;
    static final int MIN_EDGE_STRENGTH = 2;
    static final int WARM_SCORE = 35;
    private static final int TOP_PATH_LIMIT = 20;
    private static final int TOP_CONNECTOR_LIMIT = 10;
    private static final int TOP_REVERSE_INTRO_LIMIT = 20;
    private static final int MAX_VISIBLE_PEOPLE = 10_000;
    private static final int MAX_VISIBLE_EDGES = 100_000;
    private static final int MAX_NETWORK_ACCOUNT_ROWS = 50_000;
    private static final int MAX_NETWORK_EXISTING_PAIRS = 250_000;
    private static final int MAX_PATH_STATES = 250_000;
    private static final int MAX_PATH_EXPANSIONS = 500_000;
    private static final int MAX_REVERSE_CANDIDATES = 500;
    private static final int MAX_REVERSE_EDGES = 20_000;
    private static final int MAX_REVERSE_EMPLOYMENT_ROWS = 10_000;
    private static final int MAX_REVERSE_EXISTING_PAIRS = 100_000;
    private static final int MAX_REVERSE_SUGGESTIONS =
            MAX_REVERSE_CANDIDATES * (MAX_REVERSE_CANDIDATES - 1) / 2;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final Comparator<WarmIntroOpportunity> OPPORTUNITY_ORDER =
            Comparator.comparing(WarmIntroOpportunity::currency)
                    .thenComparing(WarmIntroOpportunity::opportunityValue, Comparator.reverseOrder())
                    .thenComparing(WarmIntroOpportunity::companyName)
                    .thenComparingInt(WarmIntroOpportunity::companyId);

    private final ReportMapper reportMapper;
    private final PersonMapper personMapper;
    private final PersonEdgeMapper personEdgeMapper;
    private final IntroductionMapper introductionMapper;
    private final ScoringService scoringService;

    NetworkSnapshot snapshot(ReportAggregateQuery query, boolean includeReverseIntros) {
        int workspaceId = query.workspaceId();
        List<Person> people = personMapper.getPersonsForNetworkReport(workspaceId, MAX_VISIBLE_PEOPLE + 1);
        List<PersonEdge> edges = personEdgeMapper.getEdgesForNetworkReport(workspaceId, MAX_VISIBLE_EDGES + 1);
        List<Introduction> existingPairs = introductionMapper.findExistingPairsForReport(
                workspaceId, MAX_NETWORK_EXISTING_PAIRS + 1);
        List<ReportNetworkAccountRow> accountValues = reportMapper.getNetworkAccountValues(
                query, MAX_NETWORK_ACCOUNT_ROWS + 1);
        requireWarmIntroSourceBounds(people, edges, existingPairs, accountValues);
        List<RelationshipTemperatureDto> temperatures = scoringService.scoreContacts(workspaceId);
        List<WarmIntroOpportunity> warmIntroOpportunities = rankWarmIntroOpportunities(
                people,
                edges,
                temperatures,
                existingPairs,
                accountValues);
        List<IntroSuggestionDto> reverseIntroSuggestions = includeReverseIntros
                ? reverseIntroSuggestions(workspaceId, temperatures, edges, existingPairs)
                : List.of();
        return new NetworkSnapshot(warmIntroOpportunities, reverseIntroSuggestions);
    }

    List<IntroSuggestionDto> reverseIntroSuggestions(int workspaceId) {
        List<IntroCandidatePerson> candidates = introductionMapper.findCandidatePersonsForReport(
                workspaceId, MAX_REVERSE_CANDIDATES + 1);
        requireReverseCandidateBounds(candidates);
        if (candidates.size() < 2) {
            return List.of();
        }
        List<Integer> candidateIds = candidates.stream().map(IntroCandidatePerson::getId).sorted().toList();
        Set<Integer> candidateIdSet = Set.copyOf(candidateIds);
        return rankReverseIntroSuggestions(
                candidates,
                personEdgeMapper.getEdgesForReverseIntroReport(
                        workspaceId, candidateIds, MAX_REVERSE_EDGES + 1),
                introductionMapper.findWorkspaceEmploymentForReport(
                        workspaceId, candidateIds, MAX_REVERSE_EMPLOYMENT_ROWS + 1),
                introductionMapper.findExistingPairsForReverseIntroReport(
                        workspaceId, candidateIds, MAX_REVERSE_EXISTING_PAIRS + 1),
                temperaturesByPerson(scoringService.scoreContacts(workspaceId, candidateIdSet)));
    }

    static List<ReportAggregateRow> aggregateWarmIntro(
            ReportWidgetConfig widget, List<WarmIntroOpportunity> opportunities) {
        return switch (widget.measure()) {
            case "warm_intro_opportunity_value" -> opportunityValueRows(widget.groupBy(), opportunities);
            case "warm_intro_reachable_account_count" -> reachableAccountRows(widget.groupBy(), opportunities);
            default -> throw new BadRequestException("Unsupported network report measure: " + widget.measure());
        };
    }

    static List<ReportAggregateRow> aggregateReverseIntro(
            ReportWidgetConfig widget, List<IntroSuggestionDto> suggestions) {
        if (!"reverse_intro_weighted_opportunities".equals(widget.measure())) {
            throw new BadRequestException("Unsupported reverse-intro report measure: " + widget.measure());
        }
        String group = normalizedGroup(widget.groupBy());
        if ("none".equals(group)) {
            BigDecimal total = suggestions.stream()
                    .map(ReportNetworkService::weightedSuggestionValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return List.of(new ReportAggregateRow("total", "Total", "opportunities", total));
        }
        if (!"pair".equals(group)) {
            throw new BadRequestException("Unsupported reverse-intro grouping: " + group);
        }
        return suggestions.stream()
                .sorted(Comparator.comparingInt(IntroSuggestionDto::getScore).reversed()
                        .thenComparing(Comparator.comparingInt(
                                IntroSuggestionDto::getMutualConnections).reversed())
                        .thenComparingInt(IntroSuggestionDto::getPersonAId)
                        .thenComparingInt(IntroSuggestionDto::getPersonBId))
                .limit(TOP_REVERSE_INTRO_LIMIT)
                .map(suggestion -> new ReportAggregateRow(
                        suggestion.getPersonAId() + ":" + suggestion.getPersonBId(),
                        displayName(suggestion.getPersonAName(), suggestion.getPersonAId())
                                + " ↔ "
                                + displayName(suggestion.getPersonBName(), suggestion.getPersonBId()),
                        "opportunities",
                        weightedSuggestionValue(suggestion)))
                .toList();
    }

    static List<WarmIntroOpportunity> rankWarmIntroOpportunities(
            List<Person> people,
            List<PersonEdge> edges,
            List<RelationshipTemperatureDto> temperatures,
            List<Introduction> existingPairs,
            List<ReportNetworkAccountRow> accountValues) {
        requireWarmIntroSourceBounds(people, edges, existingPairs, accountValues);
        Map<Integer, PersonNode> allNodes = personNodes(people);
        Map<Integer, Integer> scores = new HashMap<>();
        for (RelationshipTemperatureDto temperature : temperatures) {
            if (allNodes.containsKey(temperature.getId())) {
                scores.put(temperature.getId(), clampedScore(temperature.getScore()));
            }
        }

        Set<Integer> warmCompanyIds = new HashSet<>();
        for (PersonNode node : allNodes.values()) {
            if (node.companyId() != null && scores.getOrDefault(node.id(), 0) >= WARM_SCORE) {
                warmCompanyIds.add(node.companyId());
            }
        }

        Set<Integer> targetCompanyIds = new HashSet<>();
        for (ReportNetworkAccountRow account : accountValues) {
            if (!warmCompanyIds.contains(account.companyId())) {
                targetCompanyIds.add(account.companyId());
            }
        }
        if (targetCompanyIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, PersonNode> traversableNodes = new HashMap<>();
        for (PersonNode node : allNodes.values()) {
            if (!node.introExcluded()) {
                traversableNodes.put(node.id(), node);
            }
        }
        Map<Integer, List<Neighbor>> adjacency = adjacency(edges, traversableNodes.keySet());
        Set<Long> actedPairs = pairKeys(existingPairs);

        Map<PathKey, List<PathState>> frontier = new HashMap<>();
        List<PersonNode> warmEntries = traversableNodes.values().stream()
                .filter(node -> scores.getOrDefault(node.id(), 0) >= WARM_SCORE)
                .sorted(Comparator.comparingInt(PersonNode::id))
                .toList();
        for (PersonNode node : warmEntries) {
            frontier.put(new PathKey(node.id(), node.id()), List.of(new PathState(
                    List.of(node.id()), normalizedWarmth(scores.get(node.id())))));
        }
        if (frontier.isEmpty()) {
            return List.of();
        }

        Map<Integer, PathState> bestByCompany = new HashMap<>();
        int pathExpansions = 0;
        for (int hop = 1; hop <= MAX_PATH_HOPS && !frontier.isEmpty(); hop++) {
            Map<PathKey, List<PathState>> next = new HashMap<>();
            int nextStateCount = 0;
            List<PathState> orderedStates = frontier.values().stream()
                    .flatMap(List::stream)
                    .sorted(ReportNetworkService::comparePaths)
                    .toList();
            for (PathState state : orderedStates) {
                int endpoint = state.personIds().getLast();
                for (Neighbor neighbor : adjacency.getOrDefault(endpoint, List.of())) {
                    if (++pathExpansions > MAX_PATH_EXPANSIONS) {
                        throw new BadRequestException("The visible network has too many warm-intro paths");
                    }
                    PathState candidate = state.extend(neighbor.id(), normalizedEdge(neighbor.strength()));
                    nextStateCount += mergePathState(
                            next,
                            new PathKey(candidate.personIds().getFirst(), neighbor.id()),
                            candidate);
                    if (nextStateCount > MAX_PATH_STATES) {
                        throw new BadRequestException("The visible network has too many warm-intro paths");
                    }
                }
            }
            List<PathState> nextStates = next.values().stream().flatMap(List::stream).toList();
            for (PathState candidate : nextStates) {
                PersonNode target = traversableNodes.get(candidate.personIds().getLast());
                if (target == null || target.companyId() == null
                        || !targetCompanyIds.contains(target.companyId())) {
                    continue;
                }
                int entryId = candidate.personIds().getFirst();
                if (actedPairs.contains(pairKey(entryId, target.id()))) {
                    continue;
                }
                bestByCompany.merge(target.companyId(), candidate, ReportNetworkService::betterPath);
            }
            frontier = next;
        }

        List<WarmIntroOpportunity> opportunities = new ArrayList<>();
        for (ReportNetworkAccountRow account : accountValues) {
            PathState path = bestByCompany.get(account.companyId());
            if (path == null) {
                continue;
            }
            List<String> pathNames = path.personIds().stream()
                    .map(id -> displayName(traversableNodes.get(id).name(), id))
                    .toList();
            BigDecimal accountValue = nonNegative(account.accountValue());
            opportunities.add(new WarmIntroOpportunity(
                    account.companyId(),
                    displayName(account.companyName(), account.companyId()),
                    normalizedCurrency(account.currency()),
                    accountValue,
                    path.strength(),
                    accountValue.multiply(path.strength()).setScale(2, RoundingMode.HALF_UP),
                    path.personIds().getFirst(),
                    displayName(traversableNodes.get(path.personIds().getFirst()).name(), path.personIds().getFirst()),
                    path.personIds().getLast(),
                    List.copyOf(path.personIds()),
                    pathNames));
        }
        opportunities.sort(OPPORTUNITY_ORDER);
        return List.copyOf(opportunities);
    }

    private List<IntroSuggestionDto> reverseIntroSuggestions(
            int workspaceId,
            List<RelationshipTemperatureDto> temperatures,
            List<PersonEdge> edges,
            List<Introduction> existingPairs) {
        List<IntroCandidatePerson> candidates = introductionMapper.findCandidatePersonsForReport(
                workspaceId, MAX_REVERSE_CANDIDATES + 1);
        requireReverseCandidateBounds(candidates);
        if (candidates.size() < 2) {
            return List.of();
        }
        List<Integer> candidateIds = candidates.stream().map(IntroCandidatePerson::getId).sorted().toList();
        return rankReverseIntroSuggestions(
                candidates,
                edges,
                introductionMapper.findWorkspaceEmploymentForReport(
                        workspaceId, candidateIds, MAX_REVERSE_EMPLOYMENT_ROWS + 1),
                existingPairs,
                temperaturesByPerson(temperatures));
    }

    static List<IntroSuggestionDto> rankReverseIntroSuggestions(
            List<IntroCandidatePerson> candidates,
            List<PersonEdge> edges,
            List<IntroEmploymentRow> employment,
            List<Introduction> existingPairs,
            Map<Integer, RelationshipTemperatureDto> temperatures) {
        requireReverseCandidateBounds(candidates);
        Set<Integer> candidateIds = new HashSet<>();
        for (IntroCandidatePerson candidate : candidates) {
            candidateIds.add(candidate.getId());
        }
        List<PersonEdge> relevantEdges = edges.stream()
                .filter(edge -> candidateIds.contains(edge.getSourcePersonId())
                        || candidateIds.contains(edge.getTargetPersonId()))
                .toList();
        List<IntroEmploymentRow> relevantEmployment = employment.stream()
                .filter(row -> candidateIds.contains(row.getPersonId()))
                .toList();
        List<Introduction> relevantPairs = existingPairs.stream()
                .filter(pair -> candidateIds.contains(pair.getPersonAId())
                        && candidateIds.contains(pair.getPersonBId()))
                .toList();
        if (relevantEdges.size() > MAX_REVERSE_EDGES
                || relevantEmployment.size() > MAX_REVERSE_EMPLOYMENT_ROWS
                || relevantPairs.size() > MAX_REVERSE_EXISTING_PAIRS) {
            throw reverseIntroTooLarge();
        }
        return List.copyOf(IntroductionService.rankSuggestions(
                candidates,
                relevantEdges,
                relevantEmployment,
                pairKeys(relevantPairs),
                temperatures,
                MAX_REVERSE_SUGGESTIONS));
    }

    private static BadRequestException reverseIntroTooLarge() {
        return new BadRequestException("The visible network is too large to aggregate reverse introductions safely");
    }

    private static void requireReverseCandidateBounds(List<IntroCandidatePerson> candidates) {
        if (candidates.size() > MAX_REVERSE_CANDIDATES) {
            throw reverseIntroTooLarge();
        }
    }

    private static void requireWarmIntroSourceBounds(
            List<Person> people,
            List<PersonEdge> edges,
            List<Introduction> existingPairs,
            List<ReportNetworkAccountRow> accountValues) {
        if (people.size() > MAX_VISIBLE_PEOPLE
                || edges.size() > MAX_VISIBLE_EDGES
                || existingPairs.size() > MAX_NETWORK_EXISTING_PAIRS
                || accountValues.size() > MAX_NETWORK_ACCOUNT_ROWS) {
            throw new BadRequestException("The visible network is too large to aggregate safely");
        }
    }

    private static List<ReportAggregateRow> opportunityValueRows(
            String requestedGroup, List<WarmIntroOpportunity> opportunities) {
        String group = normalizedGroup(requestedGroup);
        if ("company".equals(group)) {
            Map<String, Integer> emittedByCurrency = new HashMap<>();
            List<ReportAggregateRow> rows = new ArrayList<>();
            List<WarmIntroOpportunity> ordered = opportunities.stream().sorted(OPPORTUNITY_ORDER).toList();
            for (WarmIntroOpportunity opportunity : ordered) {
                int emitted = emittedByCurrency.getOrDefault(opportunity.currency(), 0);
                if (emitted >= TOP_PATH_LIMIT) {
                    continue;
                }
                emittedByCurrency.put(opportunity.currency(), emitted + 1);
                rows.add(new ReportAggregateRow(
                            opportunity.currency() + ":" + opportunity.companyId(),
                            opportunity.currency() + " · " + opportunity.companyName()
                                    + " · " + String.join(" → ", opportunity.pathNames())
                                    + " · " + strengthLabel(opportunity.pathStrength()),
                            opportunity.currency(),
                            opportunity.opportunityValue()));
            }
            return List.copyOf(rows);
        }
        if ("connector".equals(group)) {
            Map<String, MutableAggregate> aggregates = new HashMap<>();
            for (WarmIntroOpportunity opportunity : opportunities) {
                String key = opportunity.currency() + ":" + opportunity.connectorId();
                aggregates.computeIfAbsent(key, ignored -> new MutableAggregate(
                        key,
                        opportunity.currency() + " · " + opportunity.connectorName(),
                        opportunity.currency()))
                        .add(opportunity.opportunityValue());
            }
            return sortedRowsByUnit(aggregates.values(), TOP_CONNECTOR_LIMIT);
        }
        if (!"none".equals(group)) {
            throw new BadRequestException("Unsupported warm-intro value grouping: " + group);
        }
        Map<String, BigDecimal> totals = new TreeMap<>();
        for (WarmIntroOpportunity opportunity : opportunities) {
            totals.merge(opportunity.currency(), opportunity.opportunityValue(), BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> new ReportAggregateRow(
                        entry.getKey() + ":total",
                        entry.getKey() + " · Total",
                        entry.getKey(),
                        entry.getValue()))
                .toList();
    }

    private static List<ReportAggregateRow> reachableAccountRows(
            String requestedGroup, List<WarmIntroOpportunity> opportunities) {
        String group = normalizedGroup(requestedGroup);
        Map<Integer, WarmIntroOpportunity> byCompany = new LinkedHashMap<>();
        for (WarmIntroOpportunity opportunity : opportunities) {
            byCompany.putIfAbsent(opportunity.companyId(), opportunity);
        }
        if ("none".equals(group)) {
            return List.of(new ReportAggregateRow(
                    "total", "Total", "count", BigDecimal.valueOf(byCompany.size())));
        }
        if (!"connector".equals(group)) {
            throw new BadRequestException("Unsupported warm-intro account grouping: " + group);
        }
        Map<Integer, MutableAggregate> aggregates = new HashMap<>();
        for (WarmIntroOpportunity opportunity : byCompany.values()) {
            aggregates.computeIfAbsent(opportunity.connectorId(), ignored -> new MutableAggregate(
                    Integer.toString(opportunity.connectorId()), opportunity.connectorName(), "count"))
                    .add(BigDecimal.ONE);
        }
        return sortedRows(aggregates.values(), TOP_CONNECTOR_LIMIT);
    }

    private static List<ReportAggregateRow> sortedRows(
            java.util.Collection<MutableAggregate> aggregates, int limit) {
        return aggregates.stream()
                .map(MutableAggregate::toRow)
                .sorted(Comparator.comparing(ReportAggregateRow::value).reversed()
                        .thenComparing(ReportAggregateRow::groupLabel)
                        .thenComparing(ReportAggregateRow::groupKey))
                .limit(limit)
                .toList();
    }

    private static List<ReportAggregateRow> sortedRowsByUnit(
            java.util.Collection<MutableAggregate> aggregates, int limit) {
        Map<String, List<ReportAggregateRow>> rowsByUnit = new TreeMap<>();
        for (MutableAggregate aggregate : aggregates) {
            ReportAggregateRow row = aggregate.toRow();
            rowsByUnit.computeIfAbsent(row.unit(), ignored -> new ArrayList<>()).add(row);
        }
        List<ReportAggregateRow> rows = new ArrayList<>();
        for (List<ReportAggregateRow> unitRows : rowsByUnit.values()) {
            unitRows.stream()
                    .sorted(Comparator.comparing(ReportAggregateRow::value).reversed()
                            .thenComparing(ReportAggregateRow::groupLabel)
                            .thenComparing(ReportAggregateRow::groupKey))
                    .limit(limit)
                    .forEach(rows::add);
        }
        return List.copyOf(rows);
    }

    private static Map<Integer, PersonNode> personNodes(List<Person> people) {
        Map<Integer, PersonNode> nodes = new HashMap<>();
        for (Person person : people) {
            Company company = person.getCompany();
            nodes.put(person.getId(), new PersonNode(
                    person.getId(),
                    displayName(person.getName(), person.getId()),
                    company == null ? null : company.getId(),
                    person.isIntroExcluded()));
        }
        return nodes;
    }

    private static Map<Integer, RelationshipTemperatureDto> temperaturesByPerson(
            List<RelationshipTemperatureDto> temperatures) {
        Map<Integer, RelationshipTemperatureDto> byPerson = new HashMap<>();
        for (RelationshipTemperatureDto temperature : temperatures) {
            byPerson.put(temperature.getId(), temperature);
        }
        return Map.copyOf(byPerson);
    }

    private static Map<Integer, List<Neighbor>> adjacency(
            List<PersonEdge> edges, Set<Integer> visiblePersonIds) {
        Map<Integer, List<Neighbor>> adjacency = new HashMap<>();
        for (PersonEdge edge : edges) {
            int source = edge.getSourcePersonId();
            int target = edge.getTargetPersonId();
            if (source == target || edge.getStrength() < MIN_EDGE_STRENGTH
                    || !visiblePersonIds.contains(source) || !visiblePersonIds.contains(target)) {
                continue;
            }
            adjacency.computeIfAbsent(source, ignored -> new ArrayList<>())
                    .add(new Neighbor(target, edge.getStrength()));
            adjacency.computeIfAbsent(target, ignored -> new ArrayList<>())
                    .add(new Neighbor(source, edge.getStrength()));
        }
        for (List<Neighbor> neighbors : adjacency.values()) {
            neighbors.sort(Comparator.comparingInt(Neighbor::id)
                    .thenComparing(Comparator.comparingInt(Neighbor::strength).reversed()));
        }
        return adjacency;
    }

    private static Set<Long> pairKeys(List<Introduction> pairs) {
        Set<Long> keys = new HashSet<>();
        for (Introduction pair : pairs) {
            keys.add(pairKey(pair.getPersonAId(), pair.getPersonBId()));
        }
        return keys;
    }

    private static PathState betterPath(PathState left, PathState right) {
        return comparePaths(left, right) <= 0 ? left : right;
    }

    private static int mergePathState(
            Map<PathKey, List<PathState>> statesByKey,
            PathKey key,
            PathState candidate) {
        List<PathState> states = statesByKey.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (PathState state : states) {
            if (dominates(state, candidate)) {
                return 0;
            }
        }
        int previousSize = states.size();
        states.removeIf(state -> dominates(candidate, state));
        states.add(candidate);
        return states.size() - previousSize;
    }

    private static boolean dominates(PathState left, PathState right) {
        return left.strength().compareTo(right.strength()) >= 0
                && comparePersonIds(left.personIds(), right.personIds()) <= 0;
    }

    private static int comparePaths(PathState left, PathState right) {
        int strength = right.strength().compareTo(left.strength());
        if (strength != 0) {
            return strength;
        }
        int hops = Integer.compare(left.hops(), right.hops());
        if (hops != 0) {
            return hops;
        }
        return comparePersonIds(left.personIds(), right.personIds());
    }

    private static int comparePersonIds(List<Integer> left, List<Integer> right) {
        int size = Math.min(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            int compared = Integer.compare(left.get(index), right.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static BigDecimal normalizedWarmth(int score) {
        return BigDecimal.valueOf(clampedScore(score))
                .divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalizedEdge(int strength) {
        return BigDecimal.valueOf(Math.min(3, Math.max(0, strength)))
                .divide(THREE, 6, RoundingMode.HALF_UP);
    }

    private static int clampedScore(int score) {
        return Math.min(100, Math.max(0, score));
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static String normalizedCurrency(String currency) {
        return currency == null ? "" : currency.strip().toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalizedGroup(String group) {
        return group == null || group.isBlank() ? "none" : group;
    }

    private static String displayName(String name, int id) {
        return name == null || name.isBlank() ? "#" + id : name.strip();
    }

    private static String strengthLabel(BigDecimal strength) {
        return strength.multiply(ONE_HUNDRED)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString() + "%";
    }

    private static BigDecimal weightedSuggestionValue(IntroSuggestionDto suggestion) {
        return BigDecimal.valueOf(Math.min(100, Math.max(0, suggestion.getScore()))).movePointLeft(2);
    }

    private static long pairKey(int left, int right) {
        int low = Math.min(left, right);
        int high = Math.max(left, right);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    record WarmIntroOpportunity(
            int companyId,
            String companyName,
            String currency,
            BigDecimal accountValue,
            BigDecimal pathStrength,
            BigDecimal opportunityValue,
            int connectorId,
            String connectorName,
            int targetPersonId,
            List<Integer> pathPersonIds,
            List<String> pathNames) {
    }

    record NetworkSnapshot(
            List<WarmIntroOpportunity> warmIntroOpportunities,
            List<IntroSuggestionDto> reverseIntroSuggestions) {
    }

    private record PersonNode(int id, String name, Integer companyId, boolean introExcluded) {
    }

    private record Neighbor(int id, int strength) {
    }

    private record PathKey(int connectorId, int endpointId) {
    }

    private record PathState(List<Integer> personIds, BigDecimal strength) {
        int hops() {
            return personIds.size() - 1;
        }

        PathState extend(int personId, BigDecimal edgeStrength) {
            List<Integer> extended = new ArrayList<>(personIds);
            extended.add(personId);
            return new PathState(List.copyOf(extended), strength.min(edgeStrength));
        }
    }

    private static final class MutableAggregate {
        private final String key;
        private final String label;
        private final String unit;
        private BigDecimal value = BigDecimal.ZERO;

        private MutableAggregate(String key, String label, String unit) {
            this.key = key;
            this.label = label;
            this.unit = unit;
        }

        private void add(BigDecimal amount) {
            value = value.add(amount);
        }

        private ReportAggregateRow toRow() {
            return new ReportAggregateRow(key, label, unit, value);
        }
    }
}
