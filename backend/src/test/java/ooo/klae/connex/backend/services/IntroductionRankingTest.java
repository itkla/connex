package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.IntroEmploymentRow;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;

/**
 * Unit tests for the pure reverse-introduction ranking, exercised without a database.
 */
class IntroductionRankingTest {

    @Test
    void ranksAndExplainsSuggestionsExcludingDirectlyConnectedPairs() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"),
            person(3, "Cho", 300, "Initrode"),
            person(4, "Dan", 400, "Umbrella"));
        List<PersonEdge> edges = List.of(
            edge(5, 1), edge(5, 2), edge(5, 3),
            edge(1, 3));
        List<IntroEmploymentRow> employment = List.of(
            employment(1, 900, "Initech"), employment(2, 900, "Initech"),
            employment(3, 901, "Hooli"), employment(4, 901, "Hooli"));
        Map<Integer, RelationshipTemperatureDto> temps = warmAll(1, 2, 3, 4);

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, employment, Set.of(), temps, 10);

        assertEquals(3, suggestions.size());
        assertNoPair(suggestions, 1, 3);
        assertDescendingScore(suggestions);

        IntroSuggestionDto top = suggestions.get(0);
        assertEquals(1, top.getPersonAId());
        assertEquals(2, top.getPersonBId());
        assertEquals(1, top.getMutualConnections());
        assertEquals("Initech", top.getSharedCompany());
        assertEquals(List.of("mutual_connections", "shared_company"), top.getReasons());
        assertEquals(List.of(5), top.getSupportingPersonIds());
        assertEquals(List.of(105, 205), top.getSupportingEdgeIds());

        IntroSuggestionDto sharedOnly = find(suggestions, 3, 4);
        assertEquals(0, sharedOnly.getMutualConnections());
        assertEquals("Hooli", sharedOnly.getSharedCompany());
        assertEquals(List.of("shared_company"), sharedOnly.getReasons());

        IntroSuggestionDto mutualOnly = find(suggestions, 2, 3);
        assertEquals(1, mutualOnly.getMutualConnections());
        assertNull(mutualOnly.getSharedCompany());
        assertEquals(List.of("mutual_connections"), mutualOnly.getReasons());
    }

    @Test
    void doesNotSuggestSameEmployerPairWithoutAnotherSignal() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 100, "Acme"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, List.of(), List.of(), Set.of(), warmAll(1, 2), 10);

        assertTrue(suggestions.isEmpty(),
            "a shared current employer alone is not a reason to introduce two colleagues");
    }

    @Test
    void surfacesSameEmployerPairWithMutualConnectionButRanksItBelowCrossCompany() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 100, "Acme"),
            person(3, "Cho", 200, "Globex"),
            person(4, "Dan", 300, "Initrode"));
        List<PersonEdge> edges = List.of(
            edge(5, 1), edge(5, 2),
            edge(6, 3), edge(6, 4));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, List.of(), Set.of(), warmAll(1, 2, 3, 4), 10);

        IntroSuggestionDto intra = find(suggestions, 1, 2);
        IntroSuggestionDto cross = find(suggestions, 3, 4);
        assertEquals(1, intra.getMutualConnections());
        assertEquals(List.of("mutual_connections"), intra.getReasons());
        assertNull(intra.getSharedCompany());
        assertTrue(cross.getScore() > intra.getScore(),
            "an equivalent cross-company pair must rank above the intra-company one");
    }

    @Test
    void suggestsWhenOneContactHasLeftTheSharedEmployer() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 900, "Initech"),
            person(2, "Ben", 200, "Globex"));
        List<IntroEmploymentRow> employment = List.of(employment(2, 900, "Initech"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, List.of(), employment, Set.of(), warmAll(1, 2), 10);

        assertEquals(1, suggestions.size());
        assertEquals("Initech", suggestions.get(0).getSharedCompany());
        assertEquals(List.of("shared_company"), suggestions.get(0).getReasons());
    }

    @Test
    void excludesPairsAlreadyRecordedOrDismissed() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"));
        List<PersonEdge> edges = List.of(edge(5, 1), edge(5, 2));
        Set<Long> existing = Set.of(pairKey(1, 2));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, List.of(), existing, warmAll(1, 2), 10);

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void requiresAtLeastOneStructuralSignal() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, List.of(), List.of(), Set.of(), warmAll(1, 2), 10);

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void detectsSharedFormerEmployerFromEmploymentHistory() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 300, "Now Inc"),
            person(2, "Ben", 400, "Other Inc"));
        List<IntroEmploymentRow> employment = List.of(
            employment(1, 900, "Initech"),
            employment(2, 900, "Initech"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, List.of(), employment, Set.of(), warmAll(1, 2), 10);

        assertEquals(1, suggestions.size());
        assertEquals("Initech", suggestions.get(0).getSharedCompany());
        assertEquals(List.of("shared_company"), suggestions.get(0).getReasons());
    }

    @Test
    void limitTrimsToTopByScore() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"),
            person(3, "Cho", 300, "Initrode"));
        List<PersonEdge> edges = List.of(edge(5, 1), edge(5, 2), edge(5, 3));
        List<IntroEmploymentRow> employment = List.of(
            employment(1, 900, "Initech"), employment(2, 900, "Initech"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, employment, Set.of(), warmAll(1, 2, 3), 1);

        assertEquals(1, suggestions.size());
        assertEquals(1, suggestions.get(0).getPersonAId());
        assertEquals(2, suggestions.get(0).getPersonBId());
    }

    @Test
    void ambiguousEqualScoresUseStablePersonOrdering() {
        List<IntroCandidatePerson> candidates = List.of(
            person(3, "Cho", 300, "Initrode"),
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"));
        List<PersonEdge> edges = List.of(edge(9, 3), edge(9, 1), edge(9, 2));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, List.of(), Set.of(), warmAll(1, 2, 3), 10);

        assertEquals(List.of("1-2", "1-3", "2-3"), suggestions.stream()
            .map(suggestion -> suggestion.getPersonAId() + "-" + suggestion.getPersonBId())
            .toList());
    }

    @Test
    void carriesWarmthBandsForBothParties() {
        List<IntroCandidatePerson> candidates = List.of(
            person(1, "Aoi", 100, "Acme"),
            person(2, "Ben", 200, "Globex"));
        List<PersonEdge> edges = List.of(edge(5, 1), edge(5, 2));
        Map<Integer, RelationshipTemperatureDto> temps = Map.of(
            1, temperature(1, 80, "hot"),
            2, temperature(2, 40, "warm"));

        List<IntroSuggestionDto> suggestions = IntroductionService.rankSuggestions(
            candidates, edges, List.of(), Set.of(), temps, 10);

        assertEquals(1, suggestions.size());
        assertEquals("hot", suggestions.get(0).getPersonAWarmth());
        assertEquals("warm", suggestions.get(0).getPersonBWarmth());
    }

    private static void assertDescendingScore(List<IntroSuggestionDto> suggestions) {
        for (int i = 1; i < suggestions.size(); i++) {
            assertTrue(suggestions.get(i - 1).getScore() >= suggestions.get(i).getScore(),
                "suggestions must be ordered by descending score");
        }
    }

    private static void assertNoPair(List<IntroSuggestionDto> suggestions, int a, int b) {
        assertFalse(suggestions.stream().anyMatch(s -> s.getPersonAId() == a && s.getPersonBId() == b),
            "pair " + a + "-" + b + " should not be suggested");
    }

    private static IntroSuggestionDto find(List<IntroSuggestionDto> suggestions, int a, int b) {
        return suggestions.stream()
            .filter(s -> s.getPersonAId() == a && s.getPersonBId() == b)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected pair " + a + "-" + b));
    }

    private static Map<Integer, RelationshipTemperatureDto> warmAll(int... ids) {
        return java.util.Arrays.stream(ids).boxed()
            .collect(Collectors.toMap(id -> id, id -> temperature(id, 80, "hot")));
    }

    private static IntroCandidatePerson person(int id, String name, Integer companyId, String companyName) {
        IntroCandidatePerson person = new IntroCandidatePerson();
        person.setId(id);
        person.setName(name);
        person.setTitle("Title " + id);
        person.setCompanyId(companyId);
        person.setCompanyName(companyName);
        return person;
    }

    private static PersonEdge edge(int a, int b) {
        PersonEdge edge = new PersonEdge();
        edge.setId(Math.min(a, b) * 100 + Math.max(a, b));
        edge.setWorkspaceId(1);
        edge.setSourcePersonId(Math.min(a, b));
        edge.setTargetPersonId(Math.max(a, b));
        edge.setType("knows");
        edge.setStrength(2);
        return edge;
    }

    private static IntroEmploymentRow employment(int personId, Integer companyId, String companyName) {
        IntroEmploymentRow row = new IntroEmploymentRow();
        row.setPersonId(personId);
        row.setCompanyId(companyId);
        row.setCompanyName(companyName);
        return row;
    }

    private static RelationshipTemperatureDto temperature(int id, int score, String band) {
        return new RelationshipTemperatureDto(
            id, score, band, "steady", null, null, 0, null, null, "test-model", Instant.EPOCH);
    }

    private static long pairKey(int x, int y) {
        int lower = Math.min(x, y);
        int higher = Math.max(x, y);
        return ((long) lower << 32) | (higher & 0xffffffffL);
    }
}
