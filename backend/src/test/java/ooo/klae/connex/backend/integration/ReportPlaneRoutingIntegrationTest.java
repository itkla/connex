package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.LegacyRecordVisibilitySql;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/** Executes every Report crossing against a twelve-table tenant catalog containing no control tables. */
class ReportPlaneRoutingIntegrationTest {
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 1, 0, 0);
    private RecordVisibilityPlaneFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.REPORTS, true);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allFiveStatementsMatchLegacyWithoutControlTables() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("activity", "company", "company_share", "company_tag", "deal", "deal_person", "deal_tag",
            "note", "person", "person_share", "tag", "task"), fixture.controlJdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, fixture.tenantCatalog));
        List<ReadCase> cases = cases();
        List<List<?>> expected = cases.stream().<List<?>>map(this::legacy).toList();
        fixture.controlJdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE workspace_id IN ("
            + fixture.workspaceIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + ")");
        fixture.controlJdbc.update("DELETE FROM deal WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("DELETE FROM company_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("DELETE FROM person_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        for (int i = 0; i < cases.size(); i++) {
            assertEquals(expected.get(i), tenant(cases.get(i), scope), cases.get(i).toString());
        }
        assertEquals(5, cases.stream().map(ReadCase::statement).distinct().count());
        assertEquals(fixture.tenantCatalog, catalog());
        fixture.tenantContext.clear();
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void preloadedScopeKeepsReadsOnTheTenantTransactionConnection() {
        List<ReadCase> cases = cases();
        List<List<?>> expected = cases.stream().<List<?>>map(this::legacy).toList();
        fixture.installTenant();
        String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
        new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
            Connection original = DataSourceUtils.getConnection(fixture.routing);
            for (int i = 0; i < cases.size(); i++) {
                assertEquals(expected.get(i), tenant(cases.get(i), scope), cases.get(i).toString());
                assertSame(original, DataSourceUtils.getConnection(fixture.routing));
            }
            try {
                assertEquals(fixture.tenantCatalog, original.getCatalog());
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void backgroundWorkspaceScopeRestoresRouting() throws Exception {
        List<ReadCase> cases = cases();
        List<List<?>> expected = cases.stream().<List<?>>map(this::legacy).toList();
        WorkspaceMapper workspaces = fixture.workspaceMapper;
        TenantCatalogResolver resolver = mock(TenantCatalogResolver.class);
        when(resolver.resolveCatalog(fixture.orgId)).thenReturn(fixture.tenantCatalog);
        new TenantWorkScope(fixture.tenantContext, resolver, workspaces).inWorkspace(fixture.workspaceId, () -> {
            assertFalse(fixture.tenantContext.isResolved());
            String scope = fixture.scopeAccess.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            new TransactionTemplate(fixture.transactionManager).executeWithoutResult(status -> {
                for (int i = 0; i < cases.size(); i++) {
                    assertEquals(expected.get(i), tenant(cases.get(i), scope), cases.get(i).toString());
                }
            });
        });
        assertFalse(fixture.tenantContext.isResolved());
        assertEquals(fixture.defaultCatalog, catalog());
    }

    @Test
    void frozenOracleRefusesTenantCatalog() {
        fixture.installTenant();
        LegacyRecordVisibilitySql oracle = new LegacyRecordVisibilitySql(fixture.routing, fixture.defaultCatalog);
        assertThrows(IllegalStateException.class, () -> oracle.selectList("ReportMapper.getVisiblePersonIdsAt",
            fixture.reportParameters("count", "none", END, 100)));
    }

    private List<ReadCase> cases() {
        List<ReadCase> cases = new ArrayList<>();
        for (LocalDateTime end : List.of(END.minusMonths(1), END)) {
            for (boolean filtered : List.of(false, true)) {
                for (String measure : List.of("coverage_gap_count", "coverage_gap_open_pipeline_value")) {
                    for (String group : List.of("none", "company")) {
                        cases.add(new ReadCase("aggregateCoverageGaps", fixture.reportParameters(measure, group, end, 100, filtered)));
                    }
                }
                for (String measure : List.of("single_threaded_deal_count", "single_threaded_deal_value")) {
                    for (String group : List.of("none", "company", "deal")) {
                        cases.add(new ReadCase("aggregateSingleThreadedDeals", fixture.reportParameters(measure, group, end, 100, filtered)));
                    }
                }
            }
            for (String statement : List.of("getVisiblePersonIdsAt", "getVisibleCompanyIdsAt")) {
                cases.add(new ReadCase(statement, fixture.reportParameters("count", "none", end, 100)));
            }
        }
        for (boolean filtered : List.of(false, true)) {
            for (int limit : List.of(0, 1, 2, 100)) {
                cases.add(new ReadCase("getNetworkAccountValues",
                    fixture.reportParameters("warm_intro_opportunity_value", "company", END, limit, filtered)));
            }
        }
        return cases;
    }

    private List<?> legacy(ReadCase query) {
        return fixture.legacy.selectList("ReportMapper." + query.statement(), query.parameters());
    }

    private List<?> tenant(ReadCase read, String scope) {
        Map<String, Object> parameters = read.parameters();
        ReportAggregateQuery query = (ReportAggregateQuery) parameters.get("query");
        return switch (read.statement()) {
            case "aggregateCoverageGaps" -> fixture.reportMapper.aggregateCoverageGaps(query, scope,
                (LocalDateTime) parameters.get("warmthReference"), (SqlParameters) parameters.get("model"));
            case "aggregateSingleThreadedDeals" -> fixture.reportMapper.aggregateSingleThreadedDeals(query, scope);
            case "getNetworkAccountValues" -> fixture.reportMapper.getNetworkAccountValues(query, scope, (int) parameters.get("limit"));
            case "getVisiblePersonIdsAt" -> fixture.reportMapper.getVisiblePersonIdsAt(
                fixture.workspaceId, scope, (LocalDateTime) parameters.get("asOf"));
            case "getVisibleCompanyIdsAt" -> fixture.reportMapper.getVisibleCompanyIdsAt(
                fixture.workspaceId, scope, (LocalDateTime) parameters.get("asOf"));
            default -> throw new IllegalArgumentException(read.statement());
        };
    }

    private String catalog() throws Exception {
        try (Connection connection = fixture.routing.getConnection()) {
            return connection.getCatalog();
        }
    }

    private record ReadCase(String statement, Map<String, Object> parameters) {
    }
}
