package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.IntroCandidatePerson;
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
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;

class ReportNetworkServiceTest {

    @Test
    void strongestPathWinsDeterministicallyAcrossInputOrder() {
        List<Person> people = List.of(
                person(1, "Lower warmth", null, false),
                person(2, "Best connector", null, false),
                person(3, "Bridge", null, false),
                person(4, "Target", 10, false));
        List<PersonEdge> edges = List.of(
                edge(1, 4, 3),
                edge(2, 3, 3),
                edge(3, 4, 3));
        List<RelationshipTemperatureDto> scores = List.of(score(1, 80), score(2, 90), score(3, 0), score(4, 0));
        List<ReportNetworkAccountRow> accounts = List.of(account(10, "Target account", "USD", "100"));

        List<ReportNetworkService.WarmIntroOpportunity> first = ReportNetworkService.rankWarmIntroOpportunities(
                people, edges, scores, List.of(), accounts);
        List<Person> shuffledPeople = new ArrayList<>(people);
        List<PersonEdge> shuffledEdges = new ArrayList<>(edges);
        Collections.reverse(shuffledPeople);
        Collections.reverse(shuffledEdges);
        List<ReportNetworkService.WarmIntroOpportunity> second = ReportNetworkService.rankWarmIntroOpportunities(
                shuffledPeople, shuffledEdges, scores, List.of(), accounts);

        assertEquals(first, second);
        assertEquals(1, first.size());
        assertEquals(List.of(2, 3, 4), first.getFirst().pathPersonIds());
        assertDecimal("0.900000", first.getFirst().pathStrength());
        assertDecimal("90.00", first.getFirst().opportunityValue());
    }

    @Test
    void shorterPathBreaksEqualStrengthTies() {
        List<Person> people = List.of(
                person(1, "Indirect", null, false),
                person(2, "Direct", null, false),
                person(3, "Bridge", null, false),
                person(4, "Target", 10, false));
        List<PersonEdge> edges = List.of(
                edge(1, 3, 2),
                edge(3, 4, 2),
                edge(2, 4, 2));

        List<ReportNetworkService.WarmIntroOpportunity> opportunities =
                ReportNetworkService.rankWarmIntroOpportunities(
                        people,
                        edges,
                        List.of(score(1, 90), score(2, 90), score(3, 0), score(4, 0)),
                        List.of(),
                        List.of(account(10, "Target account", "USD", "90")));

        assertEquals(List.of(2, 4), opportunities.getFirst().pathPersonIds());
        assertDecimal("60.00", opportunities.getFirst().opportunityValue());
    }

    @Test
    void lexicographicPrefixSurvivesAWeakerPrefixUntilFinalBottleneck() {
        List<Person> people = List.of(
                person(1, "Target", 10, false),
                person(3, "Shared endpoint", null, false),
                person(4, "Lexicographic bridge", null, false),
                person(5, "Stronger bridge", null, false),
                person(6, "Connector", null, false));
        List<PersonEdge> edges = List.of(
                edge(6, 4, 2),
                edge(4, 3, 2),
                edge(6, 5, 3),
                edge(5, 3, 3),
                edge(3, 1, 2));

        List<ReportNetworkService.WarmIntroOpportunity> opportunities =
                ReportNetworkService.rankWarmIntroOpportunities(
                        people,
                        edges,
                        List.of(score(1, 0), score(3, 0), score(4, 0), score(5, 0), score(6, 100)),
                        List.of(),
                        List.of(account(10, "Target account", "USD", "90")));

        assertEquals(List.of(6, 4, 3, 1), opportunities.getFirst().pathPersonIds());
        assertDecimal("60.00", opportunities.getFirst().opportunityValue());
    }

    @Test
    void actedBestConnectorFallsBackToAnUnactivatedPath() {
        List<Person> people = List.of(
                person(1, "Acted connector", null, false),
                person(2, "Fallback connector", null, false),
                person(3, "Target", 10, false));
        Introduction acted = new Introduction();
        acted.setPersonAId(1);
        acted.setPersonBId(3);

        List<ReportNetworkService.WarmIntroOpportunity> opportunities =
                ReportNetworkService.rankWarmIntroOpportunities(
                        people,
                        List.of(edge(1, 3, 3), edge(2, 3, 2)),
                        List.of(score(1, 90), score(2, 80), score(3, 0)),
                        List.of(acted),
                        List.of(account(10, "Target account", "USD", "90")));

        assertEquals(List.of(2, 3), opportunities.getFirst().pathPersonIds());
        assertDecimal("60.00", opportunities.getFirst().opportunityValue());
    }

    @Test
    void exclusionsWarmTargetsActedPairsWeakEdgesAndDepthLimitFailClosed() {
        List<Person> people = List.of(
                person(1, "Connector", null, false),
                person(2, "Acted", 10, false),
                person(3, "Warm target", 20, false),
                person(4, "Excluded bridge", null, true),
                person(5, "Excluded target", 30, false),
                person(6, "Depth one", null, false),
                person(7, "Depth two", null, false),
                person(8, "Depth three", null, false),
                person(9, "Too deep", 40, false),
                person(10, "Weak target", 50, false),
                person(11, "Valid target", 60, false),
                person(12, "Warm account contact", 20, false));
        List<PersonEdge> edges = List.of(
                edge(1, 2, 3),
                edge(1, 3, 3),
                edge(1, 4, 3),
                edge(4, 5, 3),
                edge(1, 6, 3),
                edge(6, 7, 3),
                edge(7, 8, 3),
                edge(8, 9, 3),
                edge(1, 10, 1),
                edge(1, 11, 3));
        Introduction acted = new Introduction();
        acted.setPersonAId(1);
        acted.setPersonBId(2);
        List<ReportNetworkAccountRow> accounts = List.of(
                account(10, "Acted account", "USD", "100"),
                account(20, "Warm account", "USD", "100"),
                account(30, "Excluded account", "USD", "100"),
                account(40, "Deep account", "USD", "100"),
                account(50, "Weak account", "USD", "100"),
                account(60, "Valid account", "USD", "100"));

        List<ReportNetworkService.WarmIntroOpportunity> opportunities =
                ReportNetworkService.rankWarmIntroOpportunities(
                        people,
                        edges,
                        List.of(score(1, 70), score(2, 0), score(3, 0), score(4, 0), score(5, 0),
                                score(6, 0), score(7, 0), score(8, 0), score(9, 0), score(10, 0),
                                score(11, 0), score(12, 40)),
                        List.of(acted),
                        accounts);

        assertEquals(1, opportunities.size());
        assertEquals(60, opportunities.getFirst().companyId());
        assertEquals(List.of(1, 11), opportunities.getFirst().pathPersonIds());
    }

    @Test
    void suspendedNodeIsNotTraversable() {
        Person connector = person(1, "Connector", null, false);
        Person suspendedBridge = person(2, "Suspended bridge", null, false);
        suspendedBridge.setSuspendedAt(LocalDateTime.parse("2026-07-01T00:00:00"));
        Person target = person(3, "Target", 10, false);

        List<ReportNetworkService.WarmIntroOpportunity> opportunities =
            ReportNetworkService.rankWarmIntroOpportunities(
                List.of(connector, suspendedBridge, target),
                List.of(edge(1, 2, 3), edge(2, 3, 3)),
                List.of(score(1, 90), score(2, 0), score(3, 0)),
                List.of(),
                List.of(account(10, "Target account", "USD", "100")));

        assertTrue(opportunities.isEmpty());
    }

    @Test
    void aggregationSeparatesCurrenciesCountsAccountsOnceAndWeightsReverseIntros() {
        List<ReportNetworkService.WarmIntroOpportunity> opportunities = List.of(
                opportunity(10, "Account", "USD", "100", "0.8", 1, "Connector"),
                opportunity(10, "Account", "EUR", "50", "0.8", 1, "Connector"));

        List<ReportAggregateRow> valueRows = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_opportunity_value", "none"), opportunities);
        List<ReportAggregateRow> countRows = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_reachable_account_count", "none"), opportunities);
        List<ReportAggregateRow> connectorRows = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_reachable_account_count", "connector"), opportunities);

        assertEquals(2, valueRows.size());
        assertDecimal("40.00", valueRows.getFirst().value());
        assertDecimal("80.00", valueRows.getLast().value());
        assertDecimal("1", countRows.getFirst().value());
        assertDecimal("1", connectorRows.getFirst().value());

        IntroSuggestionDto stronger = suggestion(1, "Alice", 2, "Bob", 80);
        IntroSuggestionDto weaker = suggestion(3, "Carla", 4, "Daisuke", 50);
        List<ReportAggregateRow> reverseTotal = ReportNetworkService.aggregateReverseIntro(
                widget("reverse_intro_weighted_opportunities", "none"), List.of(weaker, stronger));
        List<ReportAggregateRow> reversePairs = ReportNetworkService.aggregateReverseIntro(
                widget("reverse_intro_weighted_opportunities", "pair"), List.of(weaker, stronger));

        assertDecimal("1.3", reverseTotal.getFirst().value());
        assertEquals("Alice ↔ Bob", reversePairs.getFirst().groupLabel());
        assertDecimal("0.8", reversePairs.getFirst().value());
        assertTrue(reversePairs.getFirst().value().compareTo(reversePairs.getLast().value()) > 0);
    }

    @Test
    void reverseIntroAuthoritativeTotalExceedsTheCappedGroupedSum() {
        List<IntroSuggestionDto> suggestions = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            suggestions.add(suggestion(i * 2 - 1, "A" + i, i * 2, "B" + i, 60));
        }
        List<ReportAggregateRow> total = ReportNetworkService.aggregateReverseIntro(
                widget("reverse_intro_weighted_opportunities", "none"), suggestions);
        List<ReportAggregateRow> pairs = ReportNetworkService.aggregateReverseIntro(
                widget("reverse_intro_weighted_opportunities", "pair"), suggestions);

        assertEquals(1, total.size());
        assertEquals(20, pairs.size());
        BigDecimal groupedSum = pairs.stream()
                .map(ReportAggregateRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(groupedSum.compareTo(total.getFirst().value()) < 0,
                "the top-20 display cap must not become the authoritative reverse-intro KPI total");
    }

    @Test
    void reachableAccountAuthoritativeTotalExceedsTheCappedConnectorSum() {
        List<ReportNetworkService.WarmIntroOpportunity> opportunities = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            opportunities.add(opportunity(i, "Account " + i, "USD", "100", "0.8", 100 + i, "Connector " + i));
        }
        List<ReportAggregateRow> none = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_reachable_account_count", "none"), opportunities);
        List<ReportAggregateRow> connector = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_reachable_account_count", "connector"), opportunities);

        assertDecimal("11", none.getFirst().value());
        assertEquals(10, connector.size());
        BigDecimal groupedSum = connector.stream()
                .map(ReportAggregateRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(groupedSum.compareTo(none.getFirst().value()) < 0,
                "the top-10 connector display cap must not become the authoritative reachable-account KPI");
    }

    @Test
    void reverseIntroRankingPreservesMutualConnectionTieBreak() {
        IntroSuggestionDto lowerMutual = suggestion(1, "Alice", 2, "Bob", 80);
        lowerMutual.setMutualConnections(1);
        IntroSuggestionDto higherMutual = suggestion(3, "Carla", 4, "Daisuke", 80);
        higherMutual.setMutualConnections(3);

        List<ReportAggregateRow> rows = ReportNetworkService.aggregateReverseIntro(
                widget("reverse_intro_weighted_opportunities", "pair"),
                List.of(lowerMutual, higherMutual));

        assertEquals("Carla ↔ Daisuke", rows.getFirst().groupLabel());
    }

    @Test
    void topPathLimitIsAppliedIndependentlyPerCurrency() {
        List<ReportNetworkService.WarmIntroOpportunity> opportunities = new ArrayList<>();
        for (int companyId = 1; companyId <= 21; companyId++) {
            opportunities.add(opportunity(
                    companyId, "USD account " + companyId, "USD",
                    Integer.toString(100 + companyId), "1", 1, "Connector"));
        }
        opportunities.add(opportunity(100, "EUR account", "EUR", "1", "1", 1, "Connector"));

        List<ReportAggregateRow> rows = ReportNetworkService.aggregateWarmIntro(
                widget("warm_intro_opportunity_value", "company"), opportunities);

        assertEquals(21, rows.size());
        assertEquals(1, rows.stream().filter(row -> row.groupKey().startsWith("EUR:")).count());
        assertEquals(20, rows.stream().filter(row -> row.groupKey().startsWith("USD:")).count());
        assertTrue(rows.stream().anyMatch(row -> "EUR:100".equals(row.groupKey())));
        assertTrue(rows.stream().noneMatch(row -> "USD:1".equals(row.groupKey())));
    }

    @Test
    void oversizedGraphsFailBeforeTraversal() {
        List<Person> people = new ArrayList<>();
        for (int id = 1; id <= 10_001; id++) {
            people.add(person(id, "Person " + id, null, false));
        }

        assertThrows(BadRequestException.class, () -> ReportNetworkService.rankWarmIntroOpportunities(
                people, List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void denseGraphsFailOnTotalPathExpansionWork() {
        List<Person> people = new ArrayList<>();
        List<PersonEdge> edges = new ArrayList<>();
        List<RelationshipTemperatureDto> scores = new ArrayList<>();
        int side = 80;
        for (int id = 1; id <= side; id++) {
            people.add(person(id, "Connector " + id, null, false));
            scores.add(score(id, 80));
        }
        for (int offset = 1; offset <= side; offset++) {
            int targetId = side + offset;
            people.add(person(targetId, "Target " + offset, offset == 1 ? 10 : null, false));
            scores.add(score(targetId, 0));
            for (int connectorId = 1; connectorId <= side; connectorId++) {
                edges.add(edge(connectorId, targetId, 3));
            }
        }

        assertThrows(BadRequestException.class, () -> ReportNetworkService.rankWarmIntroOpportunities(
                people,
                edges,
                scores,
                List.of(),
                List.of(account(10, "Target account", "USD", "100"))));
    }

    @Test
    void reverseIntroCandidateBoundFailsBeforePairRanking() {
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        for (int id = 1; id <= 501; id++) {
            IntroCandidatePerson candidate = new IntroCandidatePerson();
            candidate.setId(id);
            candidate.setName("Candidate " + id);
            candidates.add(candidate);
        }

        assertThrows(BadRequestException.class, () -> ReportNetworkService.rankReverseIntroSuggestions(
                candidates, List.of(), List.of(), List.of(), Map.of()));
    }

    @Test
    void reverseOnlyGenerationStopsAtBoundedCandidatesBeforeOtherReads() {
        ReportMapper reportMapper = mock(ReportMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        PersonEdgeReadService edgeReader = mock(PersonEdgeReadService.class);
        IntroductionMapper introductionMapper = mock(IntroductionMapper.class);
        ScoringService scoringService = mock(ScoringService.class);
        ReportNetworkService service = new ReportNetworkService(
                reportMapper, personMapper, edgeReader, introductionMapper, scoringService);
        List<IntroCandidatePerson> candidates = new ArrayList<>();
        for (int id = 1; id <= 501; id++) {
            IntroCandidatePerson candidate = new IntroCandidatePerson();
            candidate.setId(id);
            candidates.add(candidate);
        }
        when(introductionMapper.findCandidatePersonsForReport(7, 501)).thenReturn(candidates);

        assertThrows(BadRequestException.class, () -> service.reverseIntroSuggestions(7));

        verify(introductionMapper).findCandidatePersonsForReport(7, 501);
        verify(introductionMapper, never()).findWorkspaceEmploymentForReport(
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(edgeReader, scoringService, reportMapper, personMapper);
    }

    @Test
    void warmGenerationStopsBeforeScoringWhenBoundedSourceSignalsOverflow() {
        ReportMapper reportMapper = mock(ReportMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        PersonEdgeReadService edgeReader = mock(PersonEdgeReadService.class);
        IntroductionMapper introductionMapper = mock(IntroductionMapper.class);
        ScoringService scoringService = mock(ScoringService.class);
        ReportNetworkService service = new ReportNetworkService(
                reportMapper, personMapper, edgeReader, introductionMapper, scoringService);
        ReportAggregateQuery query = mock(ReportAggregateQuery.class);
        when(query.workspaceId()).thenReturn(7);
        List<Person> people = new ArrayList<>();
        for (int id = 1; id <= 10_001; id++) {
            people.add(person(id, "Person " + id, null, false));
        }
        when(personMapper.getPersonsForNetworkReport(7, 10_001)).thenReturn(people);
        when(edgeReader.getEdgesForNetworkReport(7, 100_001)).thenReturn(List.of());
        when(introductionMapper.findExistingPairsForReport(7, 250_001)).thenReturn(List.of());
        when(reportMapper.getNetworkAccountValues(query, 50_001)).thenReturn(List.of());

        assertThrows(BadRequestException.class, () -> service.snapshot(query, false));

        verifyNoInteractions(scoringService);
        verify(personMapper).getPersonsForNetworkReport(7, 10_001);
        verify(edgeReader).getEdgesForNetworkReport(7, 100_001);
        verify(introductionMapper).findExistingPairsForReport(7, 250_001);
        verify(reportMapper).getNetworkAccountValues(query, 50_001);
    }

    private static Person person(int id, String name, Integer companyId, boolean excluded) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        person.setIntroExcluded(excluded);
        if (companyId != null) {
            Company company = new Company();
            company.setId(companyId);
            company.setName("Company " + companyId);
            person.setCompany(company);
        }
        return person;
    }

    private static PersonEdge edge(int source, int target, int strength) {
        PersonEdge edge = new PersonEdge();
        edge.setSourcePersonId(Math.min(source, target));
        edge.setTargetPersonId(Math.max(source, target));
        edge.setStrength(strength);
        return edge;
    }

    private static RelationshipTemperatureDto score(int id, int value) {
        return new RelationshipTemperatureDto(
            id, value, "cold", "steady", null, null, 0, null, null,
            "test-model", Instant.EPOCH);
    }

    private static ReportNetworkAccountRow account(int id, String name, String currency, String value) {
        return new ReportNetworkAccountRow(id, name, currency, new BigDecimal(value));
    }

    private static ReportNetworkService.WarmIntroOpportunity opportunity(
            int companyId,
            String companyName,
            String currency,
            String accountValue,
            String strength,
            int connectorId,
            String connectorName) {
        BigDecimal value = new BigDecimal(accountValue);
        BigDecimal pathStrength = new BigDecimal(strength);
        return new ReportNetworkService.WarmIntroOpportunity(
                companyId,
                companyName,
                currency,
                value,
                pathStrength,
                value.multiply(pathStrength).setScale(2),
                connectorId,
                connectorName,
                99,
                List.of(connectorId, 99),
                List.of(connectorName, "Target"));
    }

    private static IntroSuggestionDto suggestion(
            int personAId, String personAName, int personBId, String personBName, int score) {
        IntroSuggestionDto suggestion = new IntroSuggestionDto();
        suggestion.setPersonAId(personAId);
        suggestion.setPersonAName(personAName);
        suggestion.setPersonBId(personBId);
        suggestion.setPersonBName(personBName);
        suggestion.setScore(score);
        return suggestion;
    }

    private static ReportWidgetConfig widget(String measure, String group) {
        return new ReportWidgetConfig("network", "Network", "companies", measure, group, "table");
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
