package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Executes all introduction company ceilings in a minimal tenant catalog without control tables. */
class IntroductionCandidatePlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(true, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allCandidateStatementsExecuteWithoutControlTables() throws Exception {
        assertNoControlTables();
        List<IntroCandidatePerson> expected = fixture.legacy.selectList(
            "IntroductionMapper.findCandidatePersons", fixture.parameters());
        fixture.controlJdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE workspace_id = ?",
            fixture.workspaceId);
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertEquals(sorted(expected), sorted(fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope)));
        assertEquals(sorted(expected), sorted(fixture.introductionMapper.findWarmPathCandidates(fixture.workspaceId, scope)));
        assertEquals(sorted(expected).subList(0, 3), fixture.introductionMapper.findCandidatePersonsForReport(
            fixture.workspaceId, scope, 3));
        assertEquals(7, expected.size());
        assertEquals(fixture.tenantCatalog, catalog());
        fixture.tenantContext.clear();
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void candidateScopeRestoresTheRoutedTransaction() {
        fixture.installTenant();
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(fixture.routing);
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            assertEquals(7, fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope).size());
            assertEquals(7, fixture.introductionMapper.findWarmPathCandidates(fixture.workspaceId, scope).size());
            assertEquals(3, fixture.introductionMapper.findCandidatePersonsForReport(fixture.workspaceId, scope, 3).size());
            try {
                assertEquals(fixture.tenantCatalog, original.getCatalog());
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void legacyOracleRefusesTheTenantCatalog() {
        fixture.installTenant();
        LegacyRecordVisibilitySql oracle = new LegacyRecordVisibilitySql(fixture.routing, fixture.defaultCatalog);
        assertThrows(IllegalStateException.class,
            () -> oracle.selectList("IntroductionMapper.findCandidatePersons", fixture.parameters()));
    }

    @Test
    void unresolvedBackgroundScopeReadsControlAndRestoresRouting() throws Exception {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        TenantCatalogResolver resolver = mock(TenantCatalogResolver.class);
        when(workspaceMapper.getOrgId(fixture.workspaceId)).thenReturn(fixture.orgId);
        when(resolver.resolveCatalog(fixture.orgId)).thenReturn(fixture.tenantCatalog);
        TenantWorkScope workScope = new TenantWorkScope(fixture.tenantContext, resolver, workspaceMapper);
        workScope.inWorkspace(fixture.workspaceId, () -> {
            assertFalse(fixture.tenantContext.isResolved());
            new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
                String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
                assertEquals(7, fixture.introductionMapper.findCandidatePersons(fixture.workspaceId, scope).size());
                assertEquals(7, fixture.introductionMapper.findWarmPathCandidates(fixture.workspaceId, scope).size());
                assertEquals(3, fixture.introductionMapper.findCandidatePersonsForReport(fixture.workspaceId, scope, 3).size());
            });
        });
        assertFalse(fixture.tenantContext.isResolved());
        assertEquals(fixture.defaultCatalog, catalog());
    }

    private void assertNoControlTables() throws Exception {
        for (String table : List.of("workspace", "workspace_member", "organization", "app_user")) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
    }

    private String catalog() throws Exception {
        try (Connection connection = fixture.routing.getConnection()) {
            return connection.getCatalog();
        }
    }

    private static List<IntroCandidatePerson> sorted(List<IntroCandidatePerson> people) {
        return people.stream().sorted(Comparator.comparingInt(IntroCandidatePerson::getId)).toList();
    }
}
