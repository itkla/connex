package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** One unfiltered scope survives nested conditions, restricted evaluation, and every keyset page. */
class SegmentVisibilityScopeTest {
    private final SegmentMapper segments = mock(SegmentMapper.class);
    private final PersonMapper people = mock(PersonMapper.class);
    private final PersonEdgeReadService edges = mock(PersonEdgeReadService.class);
    private final ScoringService scoring = mock(ScoringService.class);
    private final RecordVisibilityScopeControlAccess scopes = mock(RecordVisibilityScopeControlAccess.class);
    private final SegmentService service = new SegmentService(
        mock(WorkspaceService.class), mock(AuthService.class), scoring, mock(DealRiskService.class),
        segments, edges, people, mock(TagMapper.class), new SegmentCatalog(), scopes);

    @BeforeEach
    void scope() {
        when(scopes.getForWorkspace(7)).thenReturn(new RecordVisibilityScope(90, List.of(7, 8, 9)));
    }

    @Test
    void boundedEvaluationReusesScopeAcrossPagesNestedGroupsAndNegation() {
        pages();
        when(segments.companyIdsNoActivitySince(eq(7), eq("[7,8,9]"), eq(30), anyList()))
            .thenAnswer(invocation -> {
                List<Integer> candidates = invocation.getArgument(3);
                return candidates.contains(5) ? List.of() : candidates;
            });
        when(segments.companyIdsWithWarmIntro(eq(7), eq("[7,8,9]"), eq(17), anyList(), eq(2)))
            .thenAnswer(invocation -> invocation.getArgument(3));
        SegmentCondition active = predicate("no_activity");
        active.setNegate(true);
        SegmentDefinition nested = definition("all", predicate("warm_intro_available"));
        nested.setNegate(true);
        SegmentDefinition definition = definition("any", active);
        definition.setGroups(List.of(nested));

        assertEquals(List.of(5, 6), service.evaluate(7, 17, "company", definition, 2));

        var order = inOrder(segments, scopes);
        order.verify(segments).maximumEntityId(7, "company");
        order.verify(scopes).getForWorkspace(7);
        for (int after : List.of(0, 2, 4)) {
            order.verify(segments).entityIdsPage(7, "company", after, 6, 2);
            verify(segments).companyIdsNoActivitySince(7, "[7,8,9]", 30, candidates(after));
            verify(segments).companyIdsWithWarmIntro(7, "[7,8,9]", 17, candidates(after), 2);
        }
        verify(segments, never()).companyIdsInWorkspace(7);
        verifyNoMoreInteractions(scopes);
        verifyNoInteractions(people, edges);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothPersonFieldStatementsReceiveTheSameScopeAcrossNestedGroups(boolean restricted) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (restricted) {
            when(segments.personIdsMatchingIncludingRestricted(anyMap())).thenAnswer(invocation -> {
                calls.add(invocation.getArgument(0));
                return List.of(31);
            });
        } else {
            when(segments.personIdsMatching(anyMap())).thenAnswer(invocation -> {
                calls.add(invocation.getArgument(0));
                return List.of(31);
            });
        }
        SegmentDefinition definition = definition("any", company("is", "41"));
        SegmentCondition membership = company("in", null);
        membership.setValues(List.of("41", "42"));
        definition.setGroups(List.of(definition("all", membership)));

        List<Integer> result = restricted
            ? service.evaluateIncludingRestrictedPeople(7, 17, "person", definition)
            : service.evaluate(7, 17, "person", definition);

        assertEquals(List.of(31), result);
        assertEquals(2, calls.size());
        for (Map<String, Object> parameters : calls) {
            assertEquals("[7,8,9]", parameters.get("orgWorkspaceIdsJson"));
            assertEquals(7, parameters.get("workspaceId"));
            assertEquals(restricted, parameters.get("includeRestrictedPeople"));
            assertNull(parameters.get("candidateIds"));
        }
        assertEquals(41, calls.getFirst().get("id"));
        assertEquals(List.of(41, 42), calls.getLast().get("ids"));
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
        if (restricted) {
            verify(segments, never()).personIdsMatching(anyMap());
        } else {
            verify(segments, never()).personIdsMatchingIncludingRestricted(anyMap());
        }
    }

    @Test
    void singletonEvaluationKeepsTheScopeAndComplementWithinItsCandidate() {
        when(segments.entityIdInWorkspace(7, "company", 31)).thenReturn(true);
        when(segments.companyIdsNoActivitySince(7, "[7,8,9]", 30, List.of(31))).thenReturn(List.of());
        when(segments.companyIdsWithWarmIntro(7, "[7,8,9]", 17, List.of(31), 2)).thenReturn(List.of(31));
        SegmentCondition active = predicate("no_activity");
        active.setNegate(true);
        SegmentDefinition definition = definition("all", active);
        definition.setGroups(List.of(definition("any", predicate("warm_intro_available"))));

        assertTrue(service.matchesEntity(7, 17, "company", definition, 31));

        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
        verify(segments).companyIdsNoActivitySince(7, "[7,8,9]", 30, List.of(31));
        verify(segments).companyIdsWithWarmIntro(7, "[7,8,9]", 17, List.of(31), 2);
        verify(segments, never()).companyIdsInWorkspace(7);
        verifyNoInteractions(people, edges);
    }

    @Test
    void unboundedWarmIntroAndNoActivityShareOneScope() {
        PersonEdge edge = new PersonEdge();
        edge.setSourcePersonId(21);
        edge.setTargetPersonId(22);
        edge.setStrength(2);
        when(people.getEngagedPersonIds(7)).thenReturn(List.of(21));
        when(edges.getAllEdges(7)).thenReturn(List.of(edge));
        when(segments.companyIdsForPersonsWithoutUserActivity(7, "[7,8,9]", 17, List.of(22), null))
            .thenReturn(List.of(31));
        when(segments.companyIdsNoActivitySince(7, "[7,8,9]", 30, null)).thenReturn(List.of(31));

        assertEquals(List.of(31), service.evaluate(7, 17, "company",
            definition("all", predicate("no_activity"), predicate("warm_intro_available"))));

        var order = inOrder(scopes, people, edges, segments);
        order.verify(scopes).getForWorkspace(7);
        order.verify(segments).companyIdsNoActivitySince(7, "[7,8,9]", 30, null);
        order.verify(people).getEngagedPersonIds(7);
        order.verify(edges).getAllEdges(7);
        order.verify(segments).companyIdsForPersonsWithoutUserActivity(7, "[7,8,9]", 17, List.of(22), null);
        verifyNoMoreInteractions(scopes);
    }

    @Test
    void eachPageStillCachesItsSignalsAcrossSiblingAndNestedConditions() {
        pages();
        when(scoring.scoreCompanies(eq(7), anySet())).thenReturn(List.of());
        SegmentDefinition definition = definition("any", predicate("warmth_hot"), predicate("warmth_warm"));
        definition.setGroups(List.of(definition("any", predicate("warmth_cold"), predicate("cooling"))));

        assertEquals(List.of(), service.evaluate(7, 17, "company", definition, 2));

        for (int after : List.of(0, 2, 4)) {
            verify(scoring).scoreCompanies(eq(7), eq(new HashSet<>(candidates(after))));
        }
        verifyNoMoreInteractions(scoring);
        verify(scopes).getForWorkspace(7);
        verifyNoMoreInteractions(scopes);
    }

    @Test
    void absentDefinitionsAndUnavailableEntitiesDoNotResolveScope() {
        assertEquals(List.of(), service.evaluate(7, 17, "person", null));
        assertEquals(List.of(), service.evaluateIncludingRestrictedPeople(7, 17, "person", null));
        assertEquals(List.of(), service.evaluate(7, 17, "person", null, 2));
        assertFalse(service.matchesEntity(7, 17, "person", null, 31));
        assertFalse(service.matchesEntity(7, 17, "person", definition("all", company("is", "41")), 31));
        verifyNoInteractions(scopes);
    }

    private void pages() {
        when(segments.maximumEntityId(7, "company")).thenReturn(6);
        for (int after : List.of(0, 2, 4)) {
            when(segments.entityIdsPage(7, "company", after, 6, 2)).thenReturn(candidates(after));
        }
    }

    private static List<Integer> candidates(int after) {
        return List.of(after + 1, after + 2);
    }

    private static SegmentCondition predicate(String key) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey(key);
        return condition;
    }

    private static SegmentCondition company(String op, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("company");
        condition.setOp(op);
        condition.setValue(value);
        return condition;
    }

    private static SegmentDefinition definition(String match, SegmentCondition... conditions) {
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch(match);
        definition.setConditions(List.of(conditions));
        return definition;
    }
}
