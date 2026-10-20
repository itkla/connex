package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.PersonVisibilityParity;
import ooo.klae.connex.backend.support.PersonVisibilityParity.ReadCase;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Exact Person statement parity over owned, shared, forged and lifecycle-restricted populations. */
class PersonVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.PERSONS, false);
        jdbc = fixture.controlJdbc;
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) fixture.close();
    }

    @Test
    void allTwentyNineStatementsMatchEveryPersonAndEmployerCeiling() {
        assertEquals(29, PersonVisibilityParity.STATEMENTS.size());
        for (int index = 0; index < fixture.personIds.size(); index++) {
            parity(PersonVisibilityParity.cases(fixture, index));
        }
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        for (int index = 0; index < 5; index++) {
            assertEquals(List.of(0, 1, 4).contains(index), fixture.personMapper.exists(
                fixture.workspaceId, scope, fixture.personIds.get(index)));
        }
        for (int company : fixture.companyIds) {
            jdbc.update("UPDATE person SET company_id = ? WHERE id = ?", company, fixture.personIds.getFirst());
            parity(PersonVisibilityParity.cases(fixture, 0));
        }
    }

    @Test
    void archivedPeopleStillMaskEmployersAndOrganizationLifecycleDoesNotTightenVisibility() {
        jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        jdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.workspaceId);
        for (int company : fixture.companyIds) {
            jdbc.update("UPDATE person SET company_id = ? WHERE id = ?", company, fixture.personIds.get(5));
            parity(PersonVisibilityParity.cases(fixture, 5));
        }
        for (int index = 0; index < fixture.personIds.size(); index++) {
            parity(PersonVisibilityParity.cases(fixture, index));
        }
    }

    @Test
    void filtersCountsOrderingSourceCapsAndPageLimitsKeepTheirLegacySemantics() {
        List<ReadCase> cases = new ArrayList<>();
        for (ReadCase base : PersonVisibilityParity.cases(fixture, 0)) {
            for (int limit : List.of(0, 1, 2, 100)) {
                for (boolean archived : List.of(false, true)) {
                    var parameters = new java.util.HashMap<>(base.parameters());
                    parameters.put("limit", limit);
                    parameters.put("sourceLimit", limit);
                    parameters.put("offset", 1);
                    parameters.put("archived", archived);
                    parameters.put("noCompany", true);
                    parameters.put("sort", "company");
                    parameters.put("dir", "desc");
                    cases.add(new ReadCase(base.statement(), parameters));
                }
            }
        }
        for (ReadCase base : PersonVisibilityParity.cases(fixture, 0)) {
            if (!java.util.Set.of("getPersonsPage", "countPersons", "getPersonIdsFiltered", "getPersonsFiltered").contains(base.statement())) {
                continue;
            }
            for (String sort : List.of("name", "email", "phone", "title", "company", "createdAt", "updatedAt", "warmth")) {
                for (String direction : List.of("asc", "desc")) {
                    for (String members : List.of("", "me", "unassigned")) {
                        var parameters = new java.util.HashMap<>(base.parameters());
                        parameters.put("sort", sort);
                        parameters.put("dir", direction);
                        parameters.put("memberScope", ooo.klae.connex.backend.dto.MemberScope.fromRequest(members, null, fixture.userId));
                        parameters.put("companies", List.of("Note Employer 1"));
                        parameters.put("titles", List.of("Director"));
                        parameters.put("lifecycleStages", List.of(ooo.klae.connex.backend.beans.PersonLifecycleStage.NEW));
                        parameters.put("leadSources", List.of(ooo.klae.connex.backend.beans.PersonLeadSource.EVENT));
                        parameters.put("noFirstResponse", true);
                        parameters.put("limit", 1);
                        cases.add(new ReadCase(base.statement(), parameters));
                    }
                }
            }
        }
        parity(cases);
    }

    private void parity(List<ReadCase> cases) {
        List<List<?>> expected = cases.stream().<List<?>>map(read -> PersonVisibilityParity.legacy(fixture, read)).toList();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            for (int index = 0; index < cases.size(); index++) {
                assertEquals(expected.get(index), PersonVisibilityParity.tenant(fixture, cases.get(index), scope), cases.get(index).toString());
            }
        });
    }
}
