package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Executes all five Segment statements in a nine-table tenant catalog without control tables. */
class SegmentPlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.SEGMENTS, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allFiveStatementsKeepTheLegacyMatrixWithoutControlTables() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("activity", "company", "company_share", "deal", "note", "person", "person_edge", "person_share", "task"),
            fixture.controlJdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? ORDER BY table_name", String.class, fixture.tenantCatalog));
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(parameters());
        Map<String, Object> otherActor = parameters();
        otherActor.put("userId", fixture.otherUserId);
        cases.add(otherActor);
        Map<String, Object> bounded = parameters();
        bounded.put("candidateIds", List.of(fixture.candidateCompanyIds.get(1), fixture.candidateCompanyIds.get(2),
            fixture.candidateCompanyIds.get(3), fixture.candidateCompanyIds.get(4)));
        bounded.put("fieldCandidateIds", List.of(fixture.companyFieldPersonIds.get(1), fixture.companyFieldPersonIds.get(3)));
        cases.add(bounded);
        for (int company : fixture.companyIds) {
            Map<String, Object> values = parameters();
            values.put("op", "is");
            values.put("id", company);
            cases.add(values);
        }
        List<Map<String, List<Integer>>> expected = cases.stream().map(this::legacy).toList();
        fixture.controlJdbc.update("UPDATE company SET archived_at = '2026-01-01' WHERE workspace_id = ?", fixture.workspaceId);
        for (String table : List.of("activity", "note", "task", "person_edge")) {
            fixture.controlJdbc.update("DELETE FROM " + table + " WHERE workspace_id = ?", fixture.workspaceId);
        }
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        for (int i = 0; i < cases.size(); i++) {
            assertEquals(5, expected.get(i).size());
            assertEquals(expected.get(i), tenant(cases.get(i), scope), "matrix case " + i);
        }
        assertEquals(fixture.tenantCatalog, catalog());
        fixture.tenantContext.clear();
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void restrictedCompanyConditionsStillExecuteBothStatementsWithoutControlTables() {
        for (String restriction : List.of("suspended_at", "provision_ceased_at", "archived_at")) {
            int person = fixture.companyFieldPersonIds.get(1);
            fixture.controlJdbc.update("UPDATE person SET " + restriction + " = '2026-01-01' WHERE id = ?", person);
            fixture.controlJdbc.update("UPDATE " + fixture.tenantCatalog + ".person SET " + restriction + " = '2026-01-01' WHERE id = ?", person);
            Map<String, Object> parameters = parameters();
            Map<String, List<Integer>> expected = legacy(parameters);
            fixture.installTenant();
            assertEquals(expected, tenant(parameters,
                fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson()), restriction);
            fixture.tenantContext.clear();
            fixture.controlJdbc.update("UPDATE person SET " + restriction + " = NULL WHERE id = ?", person);
            fixture.controlJdbc.update("UPDATE " + fixture.tenantCatalog + ".person SET " + restriction + " = NULL WHERE id = ?", person);
        }
    }

    @Test
    void scopeReadRestoresTheExistingTenantTransactionConnection() {
        fixture.installTenant();
        Map<String, Object> parameters = parameters();
        Map<String, List<Integer>> expected = legacy(parameters);
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(fixture.routing);
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            assertEquals(expected, tenant(parameters, scope));
            try {
                assertEquals(fixture.tenantCatalog, original.getCatalog());
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void unresolvedBackgroundContextReadsOneScopeAndRestoresRouting() throws Exception {
        Map<String, Object> parameters = parameters();
        Map<String, List<Integer>> expected = legacy(parameters);
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
            () -> oracle.selectList("SegmentMapper.companyIdsNoActivitySince", parameters()));
    }

    private Map<String, Object> parameters() {
        Map<String, Object> parameters = fixture.segmentParameters();
        parameters.put("fieldCandidateIds", fixture.companyFieldPersonIds);
        return parameters;
    }

    private Map<String, List<Integer>> legacy(Map<String, Object> parameters) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (String statement : List.of("companyIdsNoActivitySince", "companyIdsForPersonsWithoutUserActivity", "companyIdsWithWarmIntro")) {
            result.put(statement, fixture.legacy.<Integer>selectList("SegmentMapper." + statement, parameters).stream().sorted().toList());
        }
        Map<String, Object> fields = new HashMap<>(parameters);
        fields.put("candidateIds", parameters.get("fieldCandidateIds"));
        for (String statement : List.of("personIdsMatching", "personIdsMatchingIncludingRestricted")) {
            result.put(statement, fixture.legacy.<Integer>selectList("SegmentMapper." + statement, fields).stream().sorted().toList());
        }
        return result;
    }

    private Map<String, List<Integer>> tenant(Map<String, Object> parameters, String scope) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        result.put("companyIdsNoActivitySince", fixture.segmentMapper.companyIdsNoActivitySince(
            fixture.workspaceId, scope, (int) parameters.get("days"), ids(parameters, "candidateIds")).stream().sorted().toList());
        result.put("companyIdsForPersonsWithoutUserActivity", fixture.segmentMapper.companyIdsForPersonsWithoutUserActivity(
            fixture.workspaceId, scope, (int) parameters.get("userId"), ids(parameters, "personIds"), ids(parameters, "candidateIds")).stream().sorted().toList());
        result.put("companyIdsWithWarmIntro", fixture.segmentMapper.companyIdsWithWarmIntro(
            fixture.workspaceId, scope, (int) parameters.get("userId"), ids(parameters, "candidateIds"), (int) parameters.get("strongEdge")).stream().sorted().toList());
        Map<String, Object> fields = new HashMap<>(parameters);
        fields.put("candidateIds", parameters.get("fieldCandidateIds"));
        fields.put("orgWorkspaceIdsJson", scope);
        result.put("personIdsMatching", fixture.segmentMapper.personIdsMatching(fields).stream().sorted().toList());
        result.put("personIdsMatchingIncludingRestricted", fixture.segmentMapper.personIdsMatchingIncludingRestricted(fields).stream().sorted().toList());
        return result;
    }

    private String catalog() throws Exception {
        try (Connection connection = fixture.routing.getConnection()) {
            return connection.getCatalog();
        }
    }

    private static List<Integer> ids(Map<String, Object> parameters, String name) {
        return ((List<?>) parameters.get(name)).stream().map(Integer.class::cast).toList();
    }
}
