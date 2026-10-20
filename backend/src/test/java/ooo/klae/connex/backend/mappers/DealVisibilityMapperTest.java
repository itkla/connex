package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.support.DealVisibilityParity;
import ooo.klae.connex.backend.support.DealVisibilityParity.ReadCase;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Freezes all live Deal crossing semantics independently of the control-derived replacement. */
class DealVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate controlJdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.DEALS, false);
        controlJdbc = fixture.controlJdbc;
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allSixteenReadsMatchAcrossOwnedSharedUnsharedForeignAndRestrictedRelationships() {
        for (int index = 0; index < 8; index++) {
            parity(DealVisibilityParity.cases(fixture, index));
        }
        String scope = scope();
        var visible = fixture.dealMapper.getDealPeopleByDealId(fixture.workspaceId, scope, fixture.dealIds.getFirst());
        assertEquals(List.of(fixture.personIds.get(0), fixture.personIds.get(1), fixture.personIds.get(4)),
            visible.stream().map(link -> link.getPerson().getId()).toList());
        assertTrue(fixture.dealMapper.getDealsByPersonId(fixture.workspaceId, scope, fixture.personIds.get(2)).isEmpty());
        assertTrue(fixture.dealMapper.getDealsByPersonId(fixture.workspaceId, scope, fixture.personIds.get(3)).isEmpty());
        for (int index : List.of(2, 3)) {
            assertNull(fixture.dealMapper.getStageOutcome(fixture.workspaceId, scope, fixture.stageIds.get(index * 2)));
            assertNull(fixture.dealMapper.getLastNormalStageId(fixture.workspaceId, scope, fixture.pipelineIds.get(index)));
        }
    }

    @Test
    void filtersCountsMetricsSelectionsAndEverySortPreserveOffsetsAndLimits() {
        for (String sort : List.of("name", "value", "actual_value", "expected_close_date", "closed_at",
                "created_at", "updated_at", "stage_id", "position", "company", "pipeline", "stage", "status")) {
            for (String dir : List.of("asc", "desc")) {
                for (int limit : List.of(1, 10)) {
                    List<ReadCase> cases = DealVisibilityParity.cases(fixture, 0);
                    for (ReadCase read : cases) {
                        read.parameters().put("sort", sort);
                        read.parameters().put("dir", dir);
                        read.parameters().put("limit", limit);
                        read.parameters().put("offset", 1);
                    }
                    parity(cases);
                }
            }
        }
        for (int index = 0; index < 8; index++) {
            for (String selector : List.of("personIds", "companyIds", "pipelineIds", "stageIds", "segmentIdsJson",
                    "query", "memberScope", "noCompany")) {
                List<ReadCase> cases = DealVisibilityParity.cases(fixture, index);
                Object filter = switch (selector) {
                    case "personIds" -> List.of(fixture.personIds.get(index));
                    case "companyIds" -> List.of(fixture.companyIds.get(index));
                    case "pipelineIds" -> List.of(fixture.pipelineIds.get(Math.min(index, 4)));
                    case "stageIds" -> List.of(fixture.stageIds.get(Math.min(index, 4) * 2));
                    case "segmentIdsJson" -> List.of(fixture.dealIds.get(index)).toString();
                    case "query" -> "%Needle " + index + "%";
                    case "memberScope" -> MemberScope.allTeam();
                    case "noCompany" -> true;
                    default -> throw new IllegalArgumentException(selector);
                };
                cases.forEach(read -> read.parameters().put(selector, filter));
                parity(cases);
            }
        }
    }

    @Test
    void eachRelatedNameAndMemberPredicateIsTestedIndependently() {
        for (int index = 0; index < 8; index++) {
            for (String query : List.of("%Employer Needle " + index + "%", "%Pipeline Needle " + Math.min(index, 4) + "%",
                    "%Later Stage " + Math.min(index, 4) + "%")) {
                var cases = DealVisibilityParity.cases(fixture, index);
                cases.forEach(read -> read.parameters().put("query", query));
                parity(cases);
            }
        }
        for (MemberScope members : List.of(
                MemberScope.fromRequest("me", null, fixture.userId),
                MemberScope.fromRequest("members", List.of(fixture.userId), fixture.userId),
                MemberScope.fromRequest("unassigned", null, fixture.userId))) {
            var cases = DealVisibilityParity.cases(fixture, 0);
            cases.forEach(read -> read.parameters().put("memberScope", members));
            parity(cases);
        }
    }

    @Test
    void allThreeWritesMatchAffectedRowsAndStateForExistingAndAbsentJunctions() {
        String scope = scope();
        for (String statement : DealVisibilityParity.WRITES) {
            for (int dealIndex : List.of(0, 1, 8)) {
                for (int person : fixture.personIds) {
                    Map<String, Object> parameters = Map.of("workspaceId", fixture.workspaceId,
                        "dealId", fixture.dealIds.get(dealIndex), "personId", person, "role", "Updated role");
                    var expected = fixture.legacy.rollbackDealWrite(statement, parameters);
                    assertEquals(expected, DealVisibilityParity.tenantWrite(fixture, statement, parameters, scope), statement);
                    if (dealIndex == 8 || List.of(2, 3, 5, 6, 7).contains(fixture.personIds.indexOf(person))) {
                        assertEquals(0, expected.rows(), statement);
                    }
                }
            }
        }
    }

    @Test
    void duplicateUpsertAndUnchangedRolePreserveAffectedRowConventions() {
        for (String statement : DealVisibilityParity.WRITES) {
            Map<String, Object> parameters = Map.of("workspaceId", fixture.workspaceId,
                "dealId", fixture.dealIds.getFirst(), "personId", fixture.personIds.getFirst(), "role", "Role 0");
            assertEquals(fixture.legacy.rollbackDealWrite(statement, parameters),
                DealVisibilityParity.tenantWrite(fixture, statement, parameters, scope()), statement);
        }
    }

    @Test
    void invisibleEmployersStayNullAndHiddenCompaniesRemainInTheEmptyFacet() {
        String scope = scope();
        controlJdbc.update("DELETE FROM company_share WHERE company_id = ? AND workspace_id = ?",
            fixture.companyIds.get(1), fixture.workspaceId);
        var people = fixture.dealMapper.getDealPeopleByDealId(fixture.workspaceId, scope, fixture.dealIds.getFirst());
        assertNull(people.stream().filter(link -> link.getPerson().getId() == fixture.personIds.get(1))
            .findFirst().orElseThrow().getPerson().getCompany());
        assertTrue(fixture.dealMapper.countsByCompany(fixture.workspaceId, scope).stream()
            .anyMatch(facet -> "__empty__".equals(facet.getKey()) && facet.getCount() == 4));
        parity(DealVisibilityParity.cases(fixture, 1));
    }

    @Test
    void primaryContactRankingFiltersBeforeTheWindowAndSearchRetainsItsTenRowLimit() {
        controlJdbc.update("UPDATE person SET name = 'A hidden' WHERE id IN (?, ?, ?, ?, ?)",
            fixture.personIds.get(2), fixture.personIds.get(3), fixture.personIds.get(5),
            fixture.personIds.get(6), fixture.personIds.get(7));
        assertEquals(fixture.personIds.getFirst(), fixture.dealMapper.getPrimaryContactsByDealIds(
            fixture.workspaceId, scope(), List.of(fixture.dealIds.getFirst())).getFirst().personId());
        for (int index = 0; index < 12; index++) {
            controlJdbc.update("INSERT INTO deal (workspace_id, name, value, currency) VALUES (?, ?, 1, 'USD')",
                fixture.workspaceId, "Search Needle %02d".formatted(index));
        }
        var read = DealVisibilityParity.cases(fixture, 0).stream()
            .filter(item -> "search".equals(item.statement())).findFirst().orElseThrow();
        read.parameters().put("query", "%Search Needle%");
        assertEquals(10, DealVisibilityParity.tenant(fixture, read, scope()).size());
        parity(List.of(read));
    }

    @Test
    void sharedRecordRestrictionsApplyToReadsAndJunctionWritesIndependently() {
        int person = fixture.personIds.get(1);
        for (String restriction : List.of("archived_at", "suspended_at", "provision_ceased_at")) {
            controlJdbc.update("UPDATE person SET " + restriction + " = '2026-01-02' WHERE id = ?", person);
            parity(DealVisibilityParity.cases(fixture, 1));
            for (String statement : DealVisibilityParity.WRITES) {
                Map<String, Object> parameters = Map.of("workspaceId", fixture.workspaceId,
                    "dealId", fixture.dealIds.getFirst(), "personId", person, "role", "Restricted");
                var actual = DealVisibilityParity.tenantWrite(fixture, statement, parameters, scope());
                assertEquals(fixture.legacy.rollbackDealWrite(statement, parameters), actual);
                assertEquals(0, actual.rows());
            }
            controlJdbc.update("UPDATE person SET " + restriction + " = NULL WHERE id = ?", person);
        }
        controlJdbc.update("UPDATE company SET archived_at = '2026-01-02' WHERE id = ?", fixture.companyIds.get(1));
        parity(DealVisibilityParity.cases(fixture, 1));
        assertTrue(fixture.dealMapper.getDealPeopleByDealId(fixture.workspaceId, scope(), fixture.dealIds.getFirst())
            .stream().anyMatch(link -> link.getPerson().getId() == person && link.getPerson().getCompany() == null));
    }

    @Test
    void lifecycleDoesNotTightenTheUnfilteredOrganizationCeilingAndRevocationsRemainTenantReads() {
        controlJdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        controlJdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE org_id = ?", fixture.orgId);
        for (int index = 0; index < 8; index++) {
            parity(DealVisibilityParity.cases(fixture, index));
        }
        String scope = scope();
        controlJdbc.update("DELETE FROM person_share WHERE person_id = ? AND workspace_id = ?",
            fixture.personIds.get(1), fixture.workspaceId);
        assertFalse(fixture.dealMapper.getDealPeopleByDealId(fixture.workspaceId, scope, fixture.dealIds.getFirst())
            .stream().anyMatch(link -> link.getPerson().getId() == fixture.personIds.get(1)));
    }

    private String scope() {
        return fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
    }

    private void parity(List<ReadCase> cases) {
        String scope = scope();
        for (ReadCase read : cases) {
            assertEquals(DealVisibilityParity.legacy(fixture, read), DealVisibilityParity.tenant(fixture, read, scope),
                read.statement() + " " + read.parameters());
        }
    }
}
