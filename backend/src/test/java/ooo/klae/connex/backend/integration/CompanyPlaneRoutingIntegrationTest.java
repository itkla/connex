package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.support.CompanyVisibilityParity;
import ooo.klae.connex.backend.support.CompanyVisibilityParity.ReadCase;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture.Records;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises Company SQL, prepared scope refusals and retained locks with one routed pooled connection. */
class CompanyPlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;
    private AnnotationConfigApplicationContext controlContext;
    private CountingDataSource connections;
    private DataSourceTransactionManager transactions;
    private TenantWorkScope operation;
    private RecordVisibilityScopeControlAccess scopes;
    private SqlSessionTemplate sessions;
    private CompanyMapper companies;
    private ShareMapper shares;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(Records.COMPANIES, true, 1);
        connections = new CountingDataSource(fixture.routing);
        transactions = new DataSourceTransactionManager(connections);
        var configuration = new org.apache.ibatis.session.Configuration(new Environment(
            "company-plane", new SpringManagedTransactionFactory(), connections));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(fixture.tenantContext, true));
        configuration.addInterceptor(new TenantScopeInterceptor(fixture.tenantContext, true, true));
        for (String resource : List.of("mappers/WorkspaceMapper.xml", "mappers/CompanyMapper.xml", "mappers/ShareMapper.xml", "mappers/TagMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var workspaces = sessions.getMapper(WorkspaceMapper.class);
        var catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        when(catalogs.resolveCatalog(fixture.orgId)).thenReturn(fixture.tenantCatalog);
        var lifecycle = mock(TenantLifecycleControlMapper.class);
        controlContext = new AnnotationConfigApplicationContext();
        controlContext.register(ControlTransactions.class);
        controlContext.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
        controlContext.registerBean(TenantWorkspaceScopeControlOperations.class,
            () -> new TenantWorkspaceScopeControlOperations(workspaces, lifecycle, catalogs));
        controlContext.refresh();
        operation = new TenantWorkScope(fixture.tenantContext, catalogs, workspaces, lifecycle,
            controlContext.getBean(TenantWorkspaceScopeControlOperations.class));
        scopes = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(workspaces, operation, fixture.tenantContext, transactions), fixture.tenantContext);
        companies = sessions.getMapper(CompanyMapper.class);
        shares = sessions.getMapper(ShareMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (controlContext != null) {
            controlContext.close();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allTwentyThreeStatementsMatchFrozenVisibilityCasesWithoutControlTables() throws Exception {
        for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
            assertFalse(fixture.tenantTableExists(table), table);
        }
        assertEquals(List.of("activity", "company", "company_share", "company_tag", "deal", "note", "person",
            "person_share", "tag", "task"), fixture.controlJdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                String.class, fixture.tenantCatalog));
        List<ReadCase> cases = new ArrayList<>();
        for (int company : fixture.companyIds) {
            cases.addAll(CompanyVisibilityParity.cases(fixture, company));
        }
        cases.addAll(CompanyVisibilityParity.filteredCases(fixture));
        List<List<?>> expected = cases.stream().<List<?>>map(read -> CompanyVisibilityParity.legacy(fixture, read)).toList();
        fixture.controlJdbc.update("DELETE FROM company_share WHERE workspace_id = ?", fixture.workspaceId);
        fixture.controlJdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE id = ?", fixture.companyIds.getFirst());
        operation.inWorkspace(fixture.workspaceId, () -> {
            String json = scopes.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Connection original = DataSourceUtils.getConnection(connections);
                int before = connections.checkouts.get();
                for (int index = 0; index < cases.size(); index++) {
                    var parameters = new HashMap<>(cases.get(index).parameters());
                    parameters.put("orgWorkspaceIdsJson", json);
                    assertEquals(expected.get(index), sessions.selectList(
                        "ooo.klae.connex.backend.mappers.CompanyMapper." + cases.get(index).statement(), parameters),
                        cases.get(index).toString());
                }
                assertSame(original, DataSourceUtils.getConnection(connections));
                assertEquals(before, connections.checkouts.get());
                assertEquals(fixture.tenantCatalog, catalog(original));
            });
        });
    }

    @Test
    void visibleCompanyAndGrantStayLockedOnTheTenantConnection() {
        int company = fixture.companyIds.get(1);
        operation.inWorkspace(fixture.workspaceId, () -> {
            String json = scopes.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Connection original = DataSourceUtils.getConnection(connections);
                int before = connections.checkouts.get();
                assertNotNull(companies.getVisibleCompanyByIdForUpdate(fixture.workspaceId, json, company));
                assertEquals(company, shares.lockCompanyShareForWorkspace(company, fixture.workspaceId));
                try (Connection contender = DriverManager.getConnection(System.getenv("CONNEX_DB_URL"),
                        System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"))) {
                    contender.setCatalog(fixture.tenantCatalog);
                    contender.setAutoCommit(false);
                    for (String sql : List.of("SELECT id FROM company WHERE id = ? FOR UPDATE NOWAIT",
                            "SELECT company_id FROM company_share WHERE company_id = ? FOR UPDATE NOWAIT")) {
                        try (var read = contender.prepareStatement(sql)) {
                            read.setInt(1, company);
                            SQLException refusal = assertThrows(SQLException.class, read::executeQuery);
                            assertEquals(3572, refusal.getErrorCode());
                        }
                    }
                    contender.rollback();
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                assertSame(original, DataSourceUtils.getConnection(connections));
                assertEquals(before, connections.checkouts.get());
                assertEquals(fixture.tenantCatalog, catalog(original));
            });
        });
    }

    @Test
    void missingWorkspaceOrganizationAndCatalogSnapshotsFailBeforeAnotherCheckout() {
        operation.inWorkspace(fixture.workspaceId, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var previous = fixture.tenantContext.snapshot();
                var preparedScope = scopes.getForWorkspace(fixture.workspaceId);
                assertNotNull(preparedScope);
                assertThrows(TenantScopeSnapshotMissingException.class,
                    () -> scopes.getForWorkspace(fixture.workspaceIds.get(1)));
                assertThrows(IllegalStateException.class, () -> scopes.withWorkspaceScope(
                    fixture.workspaceId,
                    new RecordVisibilityScopeControlAccess.RecordVisibilityScope(fixture.foreignOrgId, preparedScope.workspaceIds()),
                    () -> companies.exists(fixture.workspaceId, preparedScope.workspaceIdsJson(), fixture.companyIds.getFirst())));
                int before = connections.checkouts.get();
                for (int[] identity : List.of(
                        new int[] {fixture.workspaceIds.get(1), fixture.orgId},
                        new int[] {fixture.workspaceId, fixture.foreignOrgId},
                        new int[] {fixture.workspaceId, fixture.orgId})) {
                    fixture.tenantContext.clear();
                    fixture.tenantContext.set(identity[0], identity[1], fixture.userId, "member", fixture.tenantCatalog);
                    assertThrows(TenantScopeSnapshotMissingException.class,
                        () -> scopes.getForWorkspace(fixture.workspaceId));
                }
                fixture.tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "member", "wrong_catalog");
                assertThrows(TenantScopeSnapshotMissingException.class, () -> scopes.getForWorkspace(fixture.workspaceId));
                fixture.tenantContext.restore(previous);
                assertEquals(before, connections.checkouts.get());
            });
        });
    }

    @Test
    void scalarTagAndVisibleCompanyCollectionExecuteWithoutControlTables() {
        operation.inWorkspace(fixture.workspaceId, () -> {
            String json = scopes.getForWorkspace(fixture.workspaceId).workspaceIdsJson();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var tag = sessions.getMapper(ooo.klae.connex.backend.mappers.TagMapper.class)
                    .getTagById(fixture.workspaceId, fixture.reportTagId);
                assertNotNull(tag);
                assertEquals(fixture.reportTagId, tag.getId());
                assertEquals(List.of(fixture.companyIds.get(0), fixture.companyIds.get(1), fixture.companyIds.get(4)),
                    companies.getCompaniesByTagId(fixture.workspaceId, json, tag.getId()).stream()
                        .map(ooo.klae.connex.backend.beans.Company::getId).toList());
            });
        });
    }

    @Test
    void preparedSharedPlacementScopeStaysMemoryOnlyInsideItsTransaction() {
        var prepared = operation.prepareWorkspaceScopes(fixture.workspaceId, fixture.orgId, null);
        operation.withPreparedWorkspace(fixture.workspaceId, fixture.orgId, null, prepared, () -> {
            var expected = scopes.getForWorkspace(fixture.workspaceId);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Connection original = DataSourceUtils.getConnection(connections);
                int before = connections.checkouts.get();
                for (int index = 0; index < 100; index++) {
                    assertSame(expected, scopes.getForWorkspace(fixture.workspaceId));
                }
                assertSame(original, DataSourceUtils.getConnection(connections));
                assertEquals(before, connections.checkouts.get());
                assertEquals(fixture.defaultCatalog, catalog(original));
            });
            return null;
        });
    }

    @Test
    void frozenOracleRefusesTheTenantCatalog() {
        operation.inWorkspace(fixture.workspaceId, () -> {
            var oracle = new ooo.klae.connex.backend.support.LegacyRecordVisibilitySql(fixture.routing, fixture.defaultCatalog);
            assertThrows(IllegalStateException.class, () -> oracle.selectList("CompanyMapper.getAllCompanies", fixture.parameters()));
        });
    }

    private static String catalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class ControlTransactions { }

    private static final class CountingDataSource extends DelegatingDataSource {
        private final AtomicInteger checkouts = new AtomicInteger();

        private CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            checkouts.incrementAndGet();
            return super.getConnection();
        }
    }
}
