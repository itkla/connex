package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.support.DealVisibilityParity;
import ooo.klae.connex.backend.support.DealVisibilityParity.ReadCase;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql.WriteResult;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;

/** Executes every live Deal crossing in a minimal tenant catalog containing no control tables. */
class DealPlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.DEALS, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allNineteenStatementsExecuteWithoutControlTablesAndIgnoreDefaultCatalogDecoys() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("company", "company_share", "deal", "deal_person", "person", "person_share",
            "pipeline", "pipeline_share", "stage"), fixture.controlJdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, fixture.tenantCatalog));
        List<ReadCase> reads = new ArrayList<>();
        List<List<?>> expectedReads = new ArrayList<>();
        List<Map<String, Object>> writes = new ArrayList<>();
        List<WriteResult> expectedWrites = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            for (ReadCase read : DealVisibilityParity.cases(fixture, index)) {
                reads.add(read);
                expectedReads.add(DealVisibilityParity.legacy(fixture, read));
            }
            Map<String, Object> parameters = Map.of("workspaceId", fixture.workspaceId,
                "dealId", fixture.dealIds.getFirst(), "personId", fixture.personIds.get(index), "role", "Tenant role");
            writes.add(parameters);
            for (String statement : DealVisibilityParity.WRITES) {
                expectedWrites.add(fixture.legacy.rollbackDealWrite(statement, parameters));
            }
        }
        fixture.controlJdbc.update("DELETE FROM person_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("DELETE FROM company_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("DELETE FROM pipeline_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("UPDATE deal SET name = 'Default Catalog Decoy' WHERE workspace_id = ?", fixture.workspaceId);
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        assertEquals(16, DealVisibilityParity.READS.size());
        assertEquals(3, DealVisibilityParity.WRITES.size());
        for (int index = 0; index < reads.size(); index++) {
            assertEquals(expectedReads.get(index), DealVisibilityParity.tenant(fixture, reads.get(index), scope),
                reads.get(index).statement());
        }
        int expectedIndex = 0;
        for (Map<String, Object> parameters : writes) {
            for (String statement : DealVisibilityParity.WRITES) {
                assertEquals(expectedWrites.get(expectedIndex++),
                    DealVisibilityParity.tenantWrite(fixture, statement, parameters, scope), statement);
            }
        }
    }

    @Test
    void aRefusedScopeLeavesTheTenantContextAndTransactionConnectionIntact() throws Exception {
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        var foreign = new ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope(
            fixture.foreignOrgId, List.of(fixture.workspaceId));
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            var original = DataSourceUtils.getConnection(fixture.routing);
            org.junit.jupiter.api.Assertions.assertThrows(
                ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException.class,
                () -> fixture.scopeAccess.withWorkspaceScopeRead(fixture.workspaceId, foreign,
                    () -> fixture.dealMapper.countsByCompany(fixture.workspaceId, foreign.workspaceIdsJson())));
            assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            assertEquals(fixture.tenantCatalog, fixture.tenantContext.getCatalog());
            assertFalse(fixture.dealMapper.getDealPeopleByDealId(
                fixture.workspaceId, scope, fixture.dealIds.getFirst()).isEmpty());
        });
        fixture.tenantContext.clear();
        try (var connection = fixture.routing.getConnection()) {
            assertEquals(fixture.defaultCatalog, connection.getCatalog());
        }
    }

    @Test
    void malformedStageOwnershipCannotSatisfyOutcomeOrRestorationQueries() {
        int malformed = fixture.controlJdbc.queryForObject(
            "SELECT MAX(id) + 1 FROM " + fixture.tenantCatalog + ".stage", Integer.class);
        fixture.controlJdbc.update("INSERT INTO " + fixture.tenantCatalog
            + ".stage (id, workspace_id, pipeline_id, name, position, is_success, is_failure) "
            + "VALUES (?, ?, ?, 'Malformed', 99, FALSE, FALSE)",
            malformed, fixture.workspaceId, fixture.pipelineIds.get(1));
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        org.junit.jupiter.api.Assertions.assertNull(fixture.dealMapper.getStageOutcome(fixture.workspaceId, scope, malformed));
        int last = fixture.dealMapper.getLastNormalStageId(fixture.workspaceId, scope, fixture.pipelineIds.get(1));
        assertFalse(last == malformed);
    }

    @Test
    void junctionWritesTouchOnlyTheTenantCatalogAndKeepTheLockedTenantConnection() throws Exception {
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        int dealId = fixture.dealIds.getFirst();
        int personId = fixture.personIds.getFirst();
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            var original = DataSourceUtils.getConnection(fixture.routing);
            assertEquals(dealId, fixture.dealMapper.getDealByIdForUpdate(fixture.workspaceId, dealId).getId());
            assertTrue(fixture.dealMapper.addPerson(fixture.workspaceId, scope, dealId, personId, "Tenant updated") > 0);
            assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            assertEquals("Tenant updated", fixture.dealMapper.getDealPeopleByDealId(fixture.workspaceId, scope, dealId)
                .stream().filter(link -> link.getPerson().getId() == personId).findFirst().orElseThrow().getRole());
        });
        assertEquals("Role 0", fixture.controlJdbc.queryForObject(
            "SELECT role FROM deal_person WHERE deal_id = ? AND person_id = ?", String.class, dealId, personId));
        fixture.tenantContext.clear();
        try (var connection = fixture.routing.getConnection()) {
            assertEquals(fixture.defaultCatalog, connection.getCatalog());
        }
    }
}
