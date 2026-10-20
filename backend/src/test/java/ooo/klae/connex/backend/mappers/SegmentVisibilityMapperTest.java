package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.PersonEdgeReadService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.SegmentCatalog;
import ooo.klae.connex.backend.services.SegmentService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Pins Segment visibility, attribution, restrictions, and candidate bounds to the frozen SQL. */
class SegmentVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.SEGMENTS, false);
        controlJdbc = fixture.controlJdbc;
        fixture.installTenant();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void noActivityIgnoresInvisibleEvidence() {
        Map<String, Object> parameters = fixture.segmentParameters();
        List<Integer> expected = companies(2, 3, 5, 6, 7);
        assertEquals(expected, parity("companyIdsNoActivitySince", parameters));
        parameters.put("candidateIds", null);
        assertEquals(expected, parity("companyIdsNoActivitySince", parameters));
        for (int index : List.of(2, 3)) {
            controlJdbc.update("DELETE FROM activity WHERE person_id = ?", fixture.personIds.get(index));
        }
        assertEquals(expected, parity("companyIdsNoActivitySince", parameters));
        parameters.put("candidateIds", companies(0, 2, 3));
        assertEquals(companies(2, 3), parity("companyIdsNoActivitySince", parameters));
        controlJdbc.update("DELETE FROM person_share WHERE person_id = ? AND workspace_id = ?",
            fixture.personIds.get(1), fixture.workspaceId);
        parameters.put("candidateIds", fixture.candidateCompanyIds);
        assertEquals(companies(1, 2, 3, 5, 6, 7), parity("companyIdsNoActivitySince", parameters));
    }

    @Test
    void noActivityKeepsItsCutoffAndArchivedCompanyFilter() {
        Map<String, Object> parameters = fixture.segmentParameters();
        controlJdbc.update("UPDATE activity SET timestamp = '2000-01-01' WHERE person_id = ?",
            fixture.personIds.get(1));
        assertEquals(companies(1, 2, 3, 5, 6, 7), parity("companyIdsNoActivitySince", parameters));
        controlJdbc.update("UPDATE company SET archived_at = '2026-01-01' WHERE id = ?",
            fixture.candidateCompanyIds.get(2));
        assertEquals(companies(1, 3, 5, 6, 7), parity("companyIdsNoActivitySince", parameters));
    }

    @Test
    void withoutUserActivityMatchesLegacyAcrossSharesActorsAndCandidates() {
        Map<String, Object> parameters = fixture.segmentParameters();
        assertEquals(fixture.candidateCompanyIds, parity("companyIdsForPersonsWithoutUserActivity", parameters));
        parameters.put("userId", fixture.otherUserId);
        List<Integer> expected = companies(2, 3, 5, 6, 7);
        assertEquals(expected, parity("companyIdsForPersonsWithoutUserActivity", parameters));
        controlJdbc.update("UPDATE activity SET timestamp = '2000-01-01' WHERE workspace_id = ?", fixture.workspaceId);
        assertEquals(expected, parity("companyIdsForPersonsWithoutUserActivity", parameters));
        for (int index : List.of(2, 3)) {
            controlJdbc.update("DELETE FROM activity WHERE person_id = ?", fixture.personIds.get(index));
        }
        assertEquals(expected, parity("companyIdsForPersonsWithoutUserActivity", parameters));
        parameters.put("candidateIds", companies(0, 2, 3));
        assertEquals(companies(2, 3), parity("companyIdsForPersonsWithoutUserActivity", parameters));
        parameters.put("candidateIds", null);
        parameters.put("personIds", List.of(fixture.candidatePersonIds.get(2)));
        assertEquals(companies(2), parity("companyIdsForPersonsWithoutUserActivity", parameters));
    }

    @Test
    void activityAttributionRetainsTheProcessablePersonGate() throws Exception {
        Map<String, Object> parameters = fixture.segmentParameters();
        int company = fixture.candidateCompanyIds.getFirst();
        parameters.put("candidateIds", List.of(company));
        int deal = fixture.candidateDealIds.getFirst();
        for (int personCase = -1; personCase < fixture.personIds.size(); personCase++) {
            for (boolean caller : List.of(false, true)) {
                clear("activity");
                Integer person = personCase < 0 ? null : fixture.personIds.get(personCase);
                fixture.addActivity(fixture.workspaceId, caller ? fixture.userId : fixture.otherUserId,
                    person, deal, "2999-01-01 00:00:00");
                boolean visible = personCase < 0 || List.of(0, 1, 4).contains(personCase);
                assertEquals(visible ? List.of() : List.of(company), parity("companyIdsNoActivitySince", parameters),
                    "no_activity person " + personCase + ", caller=" + caller);
                assertEquals(caller && visible ? List.of() : List.of(company),
                    parity("companyIdsForPersonsWithoutUserActivity", parameters),
                    "without activity person " + personCase + ", caller=" + caller);
            }
        }
        clear("activity");
        fixture.addActivity(fixture.workspaceId, fixture.userId, fixture.candidatePersonIds.getFirst(), null,
            "2999-01-01 00:00:00");
        assertEquals(List.of(), parity("companyIdsNoActivitySince", parameters));
        assertEquals(List.of(), parity("companyIdsForPersonsWithoutUserActivity", parameters));
        controlJdbc.update("UPDATE activity SET deal_id = ? WHERE workspace_id = ?", deal, fixture.workspaceId);
        assertEquals(List.of(), parity("companyIdsNoActivitySince", parameters));
        assertEquals(List.of(), parity("companyIdsForPersonsWithoutUserActivity", parameters));
        controlJdbc.update("UPDATE activity SET workspace_id = ?, deal_id = NULL WHERE workspace_id = ?",
            fixture.workspaceIds.get(1), fixture.workspaceId);
        assertEquals(List.of(company), parity("companyIdsNoActivitySince", parameters));
        assertEquals(List.of(company), parity("companyIdsForPersonsWithoutUserActivity", parameters));
    }

    @Test
    void warmIntroMatchesLegacyAcrossEvidenceMatrix() throws Exception {
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", companies(0));
        for (int owner : List.of(0, 1, 2, 3, 4)) {
            for (String source : List.of("activity", "note", "task", "private", "other_note", "other_activity", "other_task")) {
                clearEvidence();
                int engaged = fixture.personIds.get(owner);
                engagement(source, engaged);
                for (boolean reverse : List.of(false, true)) {
                    for (int strength : List.of(1, 2)) {
                        clear("person_edge");
                        edge(fixture.workspaceId, fixture.candidatePersonIds.getFirst(), engaged, reverse, strength);
                        boolean visible = List.of(0, 1, 4).contains(owner);
                        boolean workspaceEvidence = List.of("activity", "note", "task").contains(source);
                        List<Integer> expected = visible && workspaceEvidence && strength == 2 ? companies(0) : List.of();
                        assertEquals(expected, parity("companyIdsWithWarmIntro", parameters),
                            "owner=" + owner + ", source=" + source + ", reverse=" + reverse + ", strength=" + strength);
                    }
                }
            }
        }
    }

    @Test
    void warmIntroPreservesRestrictionsSelfEdgesAndWorkspaceOverlays() {
        Map<String, Object> parameters = fixture.segmentParameters();
        assertEquals(companies(0, 1, 4, 6), parity("companyIdsWithWarmIntro", parameters));
        for (String restriction : List.of("archived_at", "suspended_at", "provision_ceased_at")) {
            int person = fixture.candidatePersonIds.getFirst();
            controlJdbc.update("UPDATE person SET " + restriction + " = '2026-01-01' WHERE id = ?", person);
            assertEquals(companies(1, 4, 6), parity("companyIdsWithWarmIntro", parameters), restriction);
            assertFalse(parity("companyIdsForPersonsWithoutUserActivity", parameters)
                .contains(fixture.candidateCompanyIds.getFirst()), restriction);
            controlJdbc.update("UPDATE person SET " + restriction + " = NULL WHERE id = ?", person);
        }
        controlJdbc.update("UPDATE person_edge SET workspace_id = ? WHERE workspace_id = ?",
            fixture.workspaceIds.get(1), fixture.workspaceId);
        assertEquals(List.of(), parity("companyIdsWithWarmIntro", parameters));
        controlJdbc.update("UPDATE person_edge SET workspace_id = ?, target_person_id = source_person_id WHERE workspace_id = ?",
            fixture.workspaceId, fixture.workspaceIds.get(1));
        assertEquals(List.of(), parity("companyIdsWithWarmIntro", parameters));
    }

    @Test
    void boundedWarmIntroKeepsTheEngagedProvisionCeasedQuirk() {
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", companies(6));
        assertEquals(companies(6), parity("companyIdsWithWarmIntro", parameters));
        parameters.put("userId", fixture.otherUserId);
        assertEquals(companies(6), parity("companyIdsWithWarmIntro", parameters));
        controlJdbc.update("UPDATE person SET suspended_at = '2026-01-01' WHERE id = ?", fixture.personIds.get(6));
        assertEquals(List.of(), parity("companyIdsWithWarmIntro", parameters));
        controlJdbc.update("UPDATE person SET suspended_at = NULL, archived_at = '2026-01-01' WHERE id = ?",
            fixture.personIds.get(6));
        assertEquals(List.of(), parity("companyIdsWithWarmIntro", parameters));
    }

    @Test
    void warmIntroPriorCallerActivityKeepsActorAndAttributionSemantics() throws Exception {
        clearEvidence();
        engagement("note", fixture.personIds.getFirst());
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", companies(0));
        for (int personCase = -2; personCase < fixture.personIds.size(); personCase++) {
            for (boolean caller : List.of(false, true)) {
                clear("activity");
                Integer person = personCase == -2 ? fixture.candidatePersonIds.getFirst()
                    : personCase == -1 ? null : fixture.personIds.get(personCase);
                fixture.addActivity(fixture.workspaceId, caller ? fixture.userId : fixture.otherUserId,
                    person, personCase == -2 ? null : fixture.candidateDealIds.getFirst(), "2999-01-01 00:00:00");
                boolean visible = personCase < 0 || List.of(0, 1, 4).contains(personCase);
                assertEquals(caller && visible ? List.of() : companies(0), parity("companyIdsWithWarmIntro", parameters),
                    "person=" + personCase + ", caller=" + caller);
            }
        }
    }

    @Test
    void withoutUserActivityKeepsOwnedPeoplesCompanyIdsWithoutAVisibilityJoin() {
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", null);
        parameters.put("personIds", fixture.companyFieldPersonIds);
        assertEquals(fixture.companyIds, parity("companyIdsForPersonsWithoutUserActivity", parameters));
        parameters.put("candidateIds", fixture.candidateCompanyIds);
        assertEquals(List.of(), parity("companyIdsForPersonsWithoutUserActivity", parameters));
        assertEquals(List.of(), fixture.segmentMapper.companyLabels(fixture.workspaceId,
            List.of(fixture.companyIds.get(2), fixture.companyIds.get(3), fixture.companyIds.get(5))));
    }

    @Test
    void companyConditionsPreserveRestrictedModeAndCandidateFiltering() {
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", fixture.companyFieldPersonIds);
        for (String statement : List.of("personIdsMatching", "personIdsMatchingIncludingRestricted")) {
            assertEquals(fieldPeople(0, 1, 4), parity(statement, parameters));
            parameters.put("op", "is");
            for (int company = 0; company < fixture.companyIds.size(); company++) {
                parameters.put("id", fixture.companyIds.get(company));
                List<Integer> expected = List.of(0, 1, 4).contains(company) ? fieldPeople(company) : List.of();
                assertEquals(expected, parity(statement, parameters), statement + ": company " + company);
            }
            parameters.put("op", "in");
            parameters.put("candidateIds", fieldPeople(1, 2, 3));
            assertEquals(fieldPeople(1), parity(statement, parameters));
            parameters.put("candidateIds", fixture.companyFieldPersonIds);
        }
        for (String restriction : List.of("suspended_at", "provision_ceased_at", "archived_at")) {
            int person = fixture.companyFieldPersonIds.get(1);
            controlJdbc.update("UPDATE person SET " + restriction + " = '2026-01-01' WHERE id = ?", person);
            assertEquals(fieldPeople(0, 4), parity("personIdsMatching", parameters));
            assertEquals("archived_at".equals(restriction) ? fieldPeople(0, 4) : fieldPeople(0, 1, 4),
                parity("personIdsMatchingIncludingRestricted", parameters));
            controlJdbc.update("UPDATE person SET " + restriction + " = NULL WHERE id = ?", person);
        }
    }

    @Test
    void conditionAndGroupNegationRemainComplementsInsideTheOwnedUniverseAndLimit() {
        SegmentService service = service();
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("company");
        condition.setOp("in");
        condition.setValues(fixture.companyIds.stream().map(String::valueOf).toList());
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("candidateIds", null);
        for (boolean restricted : List.of(false, true)) {
            List<Integer> universe = restricted
                ? fixture.segmentMapper.personIdsInWorkspaceIncludingRestricted(fixture.workspaceId)
                : fixture.segmentMapper.personIdsInWorkspace(fixture.workspaceId);
            Set<Integer> expected = new HashSet<>(universe);
            expected.removeAll(parity(restricted ? "personIdsMatchingIncludingRestricted" : "personIdsMatching", parameters));
            for (boolean groupNegation : List.of(false, true)) {
                condition.setNegate(!groupNegation);
                SegmentDefinition definition = definition(condition);
                definition.setNegate(groupNegation);
                List<Integer> actual = restricted
                    ? service.evaluateIncludingRestrictedPeople(fixture.workspaceId, fixture.userId, "person", definition)
                    : service.evaluate(fixture.workspaceId, fixture.userId, "person", definition);
                assertEquals(expected, new HashSet<>(actual));
                if (!restricted) {
                    List<Integer> bounded = expected.stream().sorted().limit(2).toList();
                    assertEquals(bounded, service.evaluate(fixture.workspaceId, fixture.userId, "person", definition, 2));
                    for (int person : fixture.companyFieldPersonIds) {
                        assertEquals(expected.contains(person), service.matchesEntity(
                            fixture.workspaceId, fixture.userId, "person", definition, person));
                    }
                }
            }
        }
    }

    @Test
    void companyPredicateTruthNegationAndLimitsMatchTheLegacyCandidateUniverse() {
        SegmentService service = service();
        List<Integer> universe = fixture.segmentMapper.companyIdsInWorkspace(fixture.workspaceId);
        for (String predicate : List.of("no_activity", "warm_intro_available")) {
            Map<String, Object> parameters = fixture.segmentParameters();
            parameters.put("candidateIds", universe);
            Set<Integer> positive = new HashSet<>(parity("no_activity".equals(predicate)
                ? "companyIdsNoActivitySince" : "companyIdsWithWarmIntro", parameters));
            for (String negation : List.of("none", "condition", "group")) {
                SegmentCondition condition = new SegmentCondition();
                condition.setType("predicate");
                condition.setKey(predicate);
                condition.setNegate("condition".equals(negation));
                SegmentDefinition definition = definition(condition);
                definition.setNegate("group".equals(negation));
                Set<Integer> expected = new HashSet<>(positive);
                if (!"none".equals(negation)) {
                    expected = new HashSet<>(universe);
                    expected.removeAll(positive);
                }
                for (int limit : List.of(1, 2, 20)) {
                    assertEquals(expected.stream().sorted().limit(limit).toList(),
                        service.evaluate(fixture.workspaceId, fixture.userId, "company", definition, limit),
                        predicate + ": " + negation + ", limit=" + limit);
                }
                for (int company : universe) {
                    assertEquals(expected.contains(company), service.matchesEntity(
                        fixture.workspaceId, fixture.userId, "company", definition, company), predicate + ": " + negation);
                }
                if ("no_activity".equals(predicate)) {
                    assertEquals(expected, new HashSet<>(service.evaluate(
                        fixture.workspaceId, fixture.userId, "company", definition)));
                }
            }
        }
    }

    @Test
    void lifecycleDoesNotTightenAnyLegacyCeiling() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        controlJdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.workspaceIds.get(1));
        assertTrue(fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIds().contains(fixture.workspaceIds.get(4)));
        Map<String, Object> parameters = fixture.segmentParameters();
        assertEquals(companies(2, 3, 5, 6, 7), parity("companyIdsNoActivitySince", parameters));
        parameters.put("userId", fixture.otherUserId);
        assertEquals(companies(2, 3, 5, 6, 7), parity("companyIdsForPersonsWithoutUserActivity", parameters));
        parameters.put("userId", fixture.userId);
        assertEquals(companies(0, 1, 4, 6), parity("companyIdsWithWarmIntro", parameters));
        parameters.put("candidateIds", fixture.companyFieldPersonIds);
        assertEquals(fieldPeople(0, 1, 4), parity("personIdsMatching", parameters));
        assertEquals(fieldPeople(0, 1, 4), parity("personIdsMatchingIncludingRestricted", parameters));
    }

    private List<Integer> parity(String statement, Map<String, Object> parameters) {
        List<Integer> expected = fixture.legacy.<Integer>selectList("SegmentMapper." + statement, parameters)
            .stream().sorted().toList();
        Map<String, Object> scoped = new HashMap<>(parameters);
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        scoped.put("orgWorkspaceIdsJson", scope);
        List<Integer> actual = switch (statement) {
            case "companyIdsNoActivitySince" -> fixture.segmentMapper.companyIdsNoActivitySince(
                fixture.workspaceId, scope, (int) parameters.get("days"), ids(parameters, "candidateIds"));
            case "companyIdsForPersonsWithoutUserActivity" -> fixture.segmentMapper.companyIdsForPersonsWithoutUserActivity(
                fixture.workspaceId, scope, (int) parameters.get("userId"), ids(parameters, "personIds"), ids(parameters, "candidateIds"));
            case "companyIdsWithWarmIntro" -> fixture.segmentMapper.companyIdsWithWarmIntro(
                fixture.workspaceId, scope, (int) parameters.get("userId"), ids(parameters, "candidateIds"), (int) parameters.get("strongEdge"));
            case "personIdsMatching" -> fixture.segmentMapper.personIdsMatching(scoped);
            case "personIdsMatchingIncludingRestricted" -> fixture.segmentMapper.personIdsMatchingIncludingRestricted(scoped);
            default -> throw new IllegalArgumentException(statement);
        };
        assertEquals(expected, actual.stream().sorted().toList(), statement);
        return actual.stream().sorted().toList();
    }

    private void clearEvidence() {
        for (String table : List.of("activity", "note", "task")) {
            clear(table);
        }
    }

    private void clear(String table) {
        for (int workspace : fixture.workspaceIds) {
            controlJdbc.update("DELETE FROM " + table + " WHERE workspace_id = ?", workspace);
        }
    }

    private void engagement(String source, int person) throws Exception {
        int workspace = source.startsWith("other_") ? fixture.workspaceIds.get(1) : fixture.workspaceId;
        if (source.endsWith("activity")) {
            fixture.addActivity(workspace, fixture.otherUserId, person, null, "2999-01-01 00:00:00");
        } else if (source.endsWith("task")) {
            controlJdbc.update("INSERT INTO task (workspace_id, description, person_id) VALUES (?, 'Engagement', ?)", workspace, person);
        } else {
            fixture.addNote(workspace, fixture.userId, person, null, "private".equals(source) ? "private" : "workspace",
                "Engagement", "Evidence", "2026-09-01 00:00:00");
        }
    }

    private void edge(int workspace, int candidate, int engaged, boolean reverse, int strength) {
        controlJdbc.update("INSERT INTO person_edge (workspace_id, source_person_id, target_person_id, strength) VALUES (?, ?, ?, ?)",
            workspace, reverse ? engaged : candidate, reverse ? candidate : engaged, strength);
    }

    private List<Integer> companies(int... indices) {
        List<Integer> ids = new ArrayList<>();
        for (int index : indices) {
            ids.add(fixture.candidateCompanyIds.get(index));
        }
        return ids;
    }

    private List<Integer> fieldPeople(int... indices) {
        List<Integer> ids = new ArrayList<>();
        for (int index : indices) {
            ids.add(fixture.companyFieldPersonIds.get(index));
        }
        return ids;
    }

    private SegmentService service() {
        return new SegmentService(mock(WorkspaceService.class), mock(AuthService.class), mock(ScoringService.class),
            mock(DealRiskService.class), fixture.segmentMapper, mock(PersonEdgeReadService.class), mock(PersonMapper.class),
            mock(TagMapper.class), new SegmentCatalog(), fixture.scopeAccess);
    }

    private static SegmentDefinition definition(SegmentCondition condition) {
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
    }

    private static List<Integer> ids(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        return value == null ? null : ((List<?>) value).stream().map(Integer.class::cast).toList();
    }
}
