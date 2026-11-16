package ooo.klae.connex.backend.mappers;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.CompanyVisibilityParity;
import ooo.klae.connex.backend.support.CompanyVisibilityParity.ReadCase;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;

/** Freezes all Company row, count, evidence, lifecycle and limit semantics on a real migrated catalog. */
class CompanyVisibilityMapperTest {
    private RecordVisibilityPlaneFixture fixture;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.COMPANIES, false);
        jdbc = fixture.controlJdbc;
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void everyStatementMatchesFrozenSqlForOwnedSharedUnsharedForgedAndWindingCases() {
        assertEquals(23, CompanyVisibilityParity.STATEMENTS.size());
        for (int index = 0; index < 5; index++) {
            int companyId = fixture.companyIds.get(index);
            boolean visible = List.of(0, 1, 4).contains(index);
            jdbc.update("UPDATE company SET archived_at = CASE WHEN id = ? THEN NULL ELSE '2026-08-03' END "
                + "WHERE workspace_id IN (" + fixture.workspaceIds.toString().replace("[", "").replace("]", "") + ")", companyId);
            jdbc.update("UPDATE person SET company_id = ? WHERE company_id = ?", companyId,
                index == 0 ? companyId : fixture.companyIds.get(index - 1));
            List<ReadCase> cases = CompanyVisibilityParity.cases(fixture, companyId);
            parity(cases);
            jdbc.update("UPDATE company SET industry = NULL WHERE id = ?", companyId);
            parity(cases);
            jdbc.update("UPDATE company SET industry = ? WHERE id = ?", "Matrix Industry " + index, companyId);
            parity(cases);
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            assertEquals(visible ? List.of(companyId) : List.of(), fixture.companyMapper.getAllCompanies(
                fixture.workspaceId, scope).stream().map(company -> company.getId()).toList());
            assertEquals(visible, fixture.companyMapper.exists(fixture.workspaceId, scope, companyId));
            assertEquals(visible ? 1 : 0, fixture.companyMapper.getByIds(fixture.workspaceId, scope, List.of(companyId)).size());
            assertEquals(visible ? 1 : 0, fixture.companyMapper.getCompaniesByTagId(
                fixture.workspaceId, scope, fixture.reportTagId).size());
        }
    }

    @Test
    void pagesCountsSelectionsAndWarmthKeepFiltersOrderingAndBoundaryLimits() {
        parity(CompanyVisibilityParity.filteredCases(fixture));
        for (int limit : List.of(0, 1, 2, 100)) {
            List<ReadCase> cases = CompanyVisibilityParity.cases(fixture, fixture.companyIds.getFirst());
            for (ReadCase read : cases) {
                read.parameters().put("limit", limit);
                read.parameters().put("sourceLimit", limit);
            }
            parity(cases);
        }
    }

    @Test
    void personCeilingAndProcessingRestrictionsPreserveEvidenceTotalsAndContributorRanking() {
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        int company = fixture.companyIds.getFirst();
        var baseline = fixture.companyMapper.getRelationshipEvidenceTotals(
            fixture.workspaceId, scope, company, CompanyVisibilityParity.REFERENCE,
            ooo.klae.connex.backend.warmth.RelationshipWarmthModel.current().sqlParameters(), 100);
        assertEquals(18, baseline.contributorCount());
        assertEquals(15, fixture.companyMapper.getRelationshipEvidenceTotals(
            fixture.workspaceId, scope, fixture.companyIds.get(1), CompanyVisibilityParity.REFERENCE,
            ooo.klae.connex.backend.warmth.RelationshipWarmthModel.current().sqlParameters(), 100).contributorCount());
        parity(CompanyVisibilityParity.cases(fixture, fixture.companyIds.get(1)));
        int shared = fixture.personIds.get(1);
        jdbc.update("UPDATE person SET provision_ceased_at = '2026-08-03' WHERE id = ?", shared);
        parity(CompanyVisibilityParity.cases(fixture, company));
        assertEquals(baseline, fixture.companyMapper.getRelationshipEvidenceTotals(
            fixture.workspaceId, scope, company, CompanyVisibilityParity.REFERENCE,
            ooo.klae.connex.backend.warmth.RelationshipWarmthModel.current().sqlParameters(), 100));
        jdbc.update("UPDATE person SET suspended_at = '2026-08-03' WHERE id = ?", shared);
        parity(CompanyVisibilityParity.cases(fixture, company));
        assertEquals(12, fixture.companyMapper.getRelationshipEvidenceTotals(
            fixture.workspaceId, scope, company, CompanyVisibilityParity.REFERENCE,
            ooo.klae.connex.backend.warmth.RelationshipWarmthModel.current().sqlParameters(), 100).contributorCount());
        jdbc.update("UPDATE person SET suspended_at = NULL, archived_at = '2026-08-03' WHERE id = ?", shared);
        parity(CompanyVisibilityParity.cases(fixture, company));
    }

    @Test
    void workspaceAndOrganizationLifecycleDoNotTightenTheLegacyCeiling() {
        jdbc.update("UPDATE organization SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.orgId);
        jdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.workspaceId);
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertTrue(fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIds().contains(fixture.workspaceIds.get(4)));
        for (int company : fixture.companyIds) {
            parity(CompanyVisibilityParity.cases(fixture, company));
        }
        assertEquals(3, fixture.companyMapper.getAllCompanies(fixture.workspaceId, scope).size());
        assertFalse(fixture.companyMapper.exists(fixture.workspaceId, scope, fixture.companyIds.get(5)));
    }

    private void parity(List<ReadCase> cases) {
        List<List<?>> expected = cases.stream().<List<?>>map(read -> CompanyVisibilityParity.legacy(fixture, read)).toList();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            for (int index = 0; index < cases.size(); index++) {
                assertEquals(expected.get(index), CompanyVisibilityParity.tenant(fixture, scope, cases.get(index)),
                    cases.get(index).toString());
            }
        });
    }
}
