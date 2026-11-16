package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Executes all seven Note statements in a six-table tenant catalog with no control tables. */
class NotePlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.NOTES, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allSevenStatementsKeepTheLegacyMatrixWithoutControlTables() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("company", "company_share", "deal", "note", "person", "person_share"),
            fixture.controlJdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? ORDER BY table_name", String.class, fixture.tenantCatalog));
        List<Map<String, Object>> parameters = fixture.personIds.stream().map(person -> {
            Map<String, Object> values = fixture.noteParameters();
            int index = fixture.personIds.indexOf(person);
            values.put("personId", person);
            values.put("companyId", fixture.companyIds.get(index < 6 ? index : 0));
            values.put("query", "%LinkedNeedle " + index + "%");
            values.put("limit", 1);
            return values;
        }).toList();
        List<Map<String, Object>> expected = parameters.stream().map(this::legacy).toList();
        fixture.controlJdbc.update("UPDATE person SET name = 'Default Catalog Decoy' WHERE id IN ("
            + fixture.personIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + ")");
        fixture.controlJdbc.update("DELETE FROM note WHERE workspace_id = ?", fixture.workspaceId);
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        for (int i = 0; i < parameters.size(); i++) {
            assertEquals(expected.get(i), tenant(parameters.get(i), scope), "person " + i);
            assertEquals(7, expected.get(i).size());
        }
        assertEquals(fixture.tenantCatalog, catalog());
        fixture.tenantContext.clear();
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void scopeReadRestoresTheExistingTenantTransactionConnection() {
        fixture.installTenant();
        Map<String, Object> expected = legacy(fixture.noteParameters());
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(fixture.routing);
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            assertEquals(expected, tenant(fixture.noteParameters(), scope));
            try {
                assertEquals(fixture.tenantCatalog, original.getCatalog());
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void unresolvedBackgroundContextReadsOneScopeAndRestoresRouting() throws Exception {
        Map<String, Object> parameters = fixture.noteParameters();
        parameters.put("query", "%LinkedNeedle%");
        Map<String, Object> expected = legacy(parameters);
        WorkspaceMapper workspaces = fixture.workspaceMapper;
        TenantCatalogResolver resolver = mock(TenantCatalogResolver.class);
        when(resolver.resolveCatalog(fixture.orgId)).thenReturn(fixture.tenantCatalog);
        new TenantWorkScope(fixture.tenantContext, resolver, workspaces).inWorkspace(fixture.workspaceId, () -> {
            assertFalse(fixture.tenantContext.isResolved());
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status ->
                assertEquals(expected, tenant(parameters, scope)));
        });
        assertFalse(fixture.tenantContext.isResolved());
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void frozenOracleCannotAccidentallyReadTheTenantCatalog() {
        fixture.installTenant();
        LegacyRecordVisibilitySql oracle = new LegacyRecordVisibilitySql(fixture.routing, fixture.defaultCatalog);
        assertThrows(IllegalStateException.class,
            () -> oracle.selectList("NoteMapper.getVisibleNotesPage", fixture.noteParameters()));
    }

    private Map<String, Object> legacy(Map<String, Object> parameters) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String statement : List.of("getVisibleNotesPage", "getWorkspaceNotesPage", "searchVisible")) {
            result.put(statement, fixture.legacy.selectList("NoteMapper." + statement, parameters));
        }
        for (String statement : List.of("countVisibleNotes", "countWorkspaceNotes",
                "countOwnPrivateNotesForPersonEvidence", "countOwnPrivateNotesForCompanyEvidence")) {
            result.put(statement, fixture.legacy.count("NoteMapper." + statement, parameters));
        }
        return result;
    }

    private Map<String, Object> tenant(Map<String, Object> parameters, String scope) {
        String query = (String) parameters.get("query");
        int limit = (int) parameters.get("limit");
        int offset = ((Number) parameters.get("offset")).intValue();
        int sourceLimit = (int) parameters.get("sourceLimit");
        LocalDateTime reference = (LocalDateTime) parameters.get("reference");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("getVisibleNotesPage", fixture.noteMapper.getVisibleNotesPage(fixture.workspaceId, scope,
            fixture.userId, query, List.of(), "updated", "desc", limit, offset));
        result.put("getWorkspaceNotesPage", fixture.noteMapper.getWorkspaceNotesPage(fixture.workspaceId, scope,
            query, List.of(), "updated", "desc", limit, offset));
        result.put("searchVisible", fixture.noteMapper.searchVisible(fixture.workspaceId, scope, query,
            fixture.userId, limit, offset));
        result.put("countVisibleNotes", fixture.noteMapper.countVisibleNotes(fixture.workspaceId, scope,
            fixture.userId, query, List.of()));
        result.put("countWorkspaceNotes", fixture.noteMapper.countWorkspaceNotes(fixture.workspaceId, scope,
            query, List.of()));
        result.put("countOwnPrivateNotesForPersonEvidence", (long) fixture.noteMapper.countOwnPrivateNotesForPersonEvidence(
            fixture.workspaceId, scope, (int) parameters.get("personId"), fixture.userId, reference, sourceLimit));
        result.put("countOwnPrivateNotesForCompanyEvidence", (long) fixture.noteMapper.countOwnPrivateNotesForCompanyEvidence(
            fixture.workspaceId, scope, (int) parameters.get("companyId"), fixture.userId, reference, sourceLimit));
        return result;
    }

    private String catalog() throws Exception {
        try (Connection connection = fixture.routing.getConnection()) {
            return connection.getCatalog();
        }
    }
}
