package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.TenantLifecycleAccess;
import ooo.klae.connex.backend.support.RecordVisibilityPlaneFixture;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves real transaction-bound scope reads use one pooled connection and tenant-only SQL. */
class TenantScopeSnapshotPlaneRoutingIntegrationTest {
    private RecordVisibilityPlaneFixture fixture;
    private AnnotationConfigApplicationContext controlContext;
    private CountingDataSource connections;
    private DataSourceTransactionManager transactions;
    private TenantWorkScope scope;
    private ShareWorkspaceControlAccess sharing;
    private RecordVisibilityScopeControlAccess visibility;
    private OrganizationWorkspaceScopeControlAccess active;
    private IntroductionMapper introductions;
    private ShareMapper shares;
    private TenantCatalogResolver catalogs;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new RecordVisibilityPlaneFixture(true, true, 1);
        connections = new CountingDataSource(fixture.routing);
        transactions = new DataSourceTransactionManager(connections);
        var configuration = new org.apache.ibatis.session.Configuration(new Environment(
            "scope-snapshot-plane", new SpringManagedTransactionFactory(), connections));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(fixture.tenantContext, true));
        configuration.addInterceptor(new TenantScopeInterceptor(fixture.tenantContext, true, true));
        for (String resource : List.of("mappers/WorkspaceMapper.xml", "mappers/ShareMapper.xml",
                "mappers/IntroductionMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var workspaces = sessions.getMapper(WorkspaceMapper.class);
        catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        when(catalogs.resolveCatalog(fixture.orgId)).thenReturn(fixture.tenantCatalog);
        var lifecycle = mock(TenantLifecycleControlMapper.class);
        controlContext = new AnnotationConfigApplicationContext();
        controlContext.register(ControlTransactions.class);
        controlContext.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
        controlContext.registerBean(TenantWorkspaceScopeControlOperations.class,
            () -> new TenantWorkspaceScopeControlOperations(workspaces, lifecycle, catalogs));
        controlContext.refresh();
        scope = new TenantWorkScope(fixture.tenantContext, catalogs, workspaces, lifecycle,
            controlContext.getBean(TenantWorkspaceScopeControlOperations.class));
        sharing = new ShareWorkspaceControlAccess(workspaces, scope, fixture.tenantContext, transactions);
        visibility = new RecordVisibilityScopeControlAccess(sharing);
        active = new OrganizationWorkspaceScopeControlAccess(
            new OrganizationWorkspaceScopeControlOperations(workspaces), scope, fixture.tenantContext, transactions);
        introductions = sessions.getMapper(IntroductionMapper.class);
        shares = sessions.getMapper(ShareMapper.class);
    }

    @AfterEach
    void close() {
        if (controlContext != null) {
            controlContext.close();
        }
        if (fixture != null) {
            fixture.tenantContext.clear();
            fixture.close();
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneConnectionSupportsNestedRunAsAndAllThreeBoundariesWithLegacyRowsLabelsAndLimits() throws Exception {
        for (String table : List.of("workspace", "workspace_member", "organization", "app_user")) {
            assertFalse(fixture.tenantTableExists(table));
        }
        List<IntroCandidatePerson> expected = fixture.legacy.selectList(
            "IntroductionMapper.findCandidatePersons", fixture.parameters());
        expected = sorted(expected);
        fixture.controlJdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE workspace_id = ?",
            fixture.workspaceId);
        List<IntroCandidatePerson> legacy = expected;
        User actor = new User();
        actor.setId(fixture.userId);
        var automation = new AutomationExecutor(fixture.tenantContext, new AutomationScope(), scope);

        scope.inWorkspace(fixture.workspaceId, () -> {
            assertEquals(0, fixture.pool.getHikariPoolMXBean().getActiveConnections());
            assertEquals(1, connections.checkouts.get());
            new TransactionTemplate(transactions).executeWithoutResult(status -> automation.runAs(
                    fixture.workspaceId, actor, "member", () -> {
                Connection original = DataSourceUtils.getConnection(connections);
                int before = connections.checkouts.get();
                assertEquals(2, before);
                var prepared = fixture.tenantContext.getWorkspaceScopes();
                assertNotNull(prepared);
                for (int repeat = 0; repeat < 3; repeat++) {
                    assertSame(prepared.requireSharing(), sharing.getForWorkspace(fixture.workspaceId));
                    assertSame(prepared.active(), active.getForWorkspace(fixture.workspaceId));
                    assertSame(prepared.active(), active.getForOrg(fixture.orgId));
                    assertSame(visibility.getForWorkspace(fixture.workspaceId), visibility.getForWorkspace(fixture.workspaceId));
                }
                assertFalse(active.getForWorkspace(fixture.workspaceId).workspaceIds().contains(fixture.workspaceIds.get(3)));
                assertFalse(active.getForWorkspace(fixture.workspaceId).workspaceIds().contains(fixture.workspaceIds.get(4)));
                var recordScope = visibility.getForWorkspace(fixture.workspaceId);
                assertEquals(4, recordScope.workspaceIds().size());
                assertEquals(legacy, sorted(introductions.findCandidatePersons(fixture.workspaceId,
                    recordScope.workspaceIdsJson())));
                assertEquals(7, legacy.size());
                assertEquals(legacy.subList(0, 3), introductions.findCandidatePersonsForReport(
                    fixture.workspaceId, recordScope.workspaceIdsJson(), 3));
                int companyId = fixture.companyIds.getFirst();
                int sibling = fixture.workspaceIds.get(1);
                assertEquals(1, shares.shareCompany(companyId, fixture.workspaceId, sibling, fixture.userId,
                    false, sharing.getForWorkspace(fixture.workspaceId).workspaceIdsJson()));
                assertEquals(0, shares.shareCompany(companyId, fixture.workspaceId, fixture.workspaceIds.get(3),
                    fixture.userId, false, sharing.getForWorkspace(fixture.workspaceId).workspaceIdsJson()));
                assertEquals(List.of(sibling), sharing.getForWorkspace(fixture.workspaceId)
                    .hydrate(shares.listCompanyShares(fixture.workspaceId, companyId)).stream()
                    .map(row -> row.getWorkspaceId()).toList());
                assertSame(original, DataSourceUtils.getConnection(connections));
                assertEquals(fixture.tenantCatalog, catalog(original));
                assertEquals(before, connections.checkouts.get());
                assertEquals(1, fixture.pool.getHikariPoolMXBean().getActiveConnections());
                status.setRollbackOnly();
                return null;
            }));
        });
        assertNull(fixture.tenantContext.getWorkspaceScopes());
        assertEquals(0, fixture.pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void missingWrongWorkspaceAndWrongOrgRefuseBeforeAnySecondCheckout() {
        scope.inWorkspace(fixture.workspaceId, () -> {
            fixture.tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "member", fixture.tenantCatalog);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var previous = fixture.tenantContext.snapshot();
                Connection original = DataSourceUtils.getConnection(connections);
                int before = connections.checkouts.get();
                assertThrows(TenantScopeSnapshotMissingException.class,
                    () -> visibility.getForWorkspace(fixture.workspaceIds.get(1)));
                assertThrows(TenantScopeSnapshotMissingException.class,
                    () -> active.getForOrg(fixture.foreignOrgId));
                fixture.tenantContext.set(fixture.workspaceId, fixture.foreignOrgId, fixture.userId, "member", fixture.tenantCatalog);
                assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForWorkspace(fixture.workspaceId));
                fixture.tenantContext.clear();
                fixture.tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "member", fixture.tenantCatalog);
                assertThrows(TenantScopeSnapshotMissingException.class, () -> sharing.getForWorkspace(fixture.workspaceId));
                fixture.tenantContext.restore(previous);
                assertSame(original, DataSourceUtils.getConnection(connections));
                assertEquals(before, connections.checkouts.get());
            });
        });
    }

    @Test
    void unavailableLifecycleAnchorIsAnInstalledResultWithoutLaterQueries() {
        fixture.controlJdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", fixture.workspaceId);
        var prepared = scope.prepareWorkspaceScopes(fixture.workspaceId, fixture.orgId, fixture.tenantCatalog);
        scope.withPreparedWorkspace(fixture.workspaceId, fixture.orgId, fixture.tenantCatalog, prepared, () -> {
            fixture.tenantContext.set(fixture.workspaceId, fixture.orgId, fixture.userId, "org_admin", fixture.tenantCatalog);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                int before = connections.checkouts.get();
                assertSame(prepared.requireSharing(), sharing.getForWorkspace(fixture.workspaceId));
                assertThrows(IllegalStateException.class, () -> active.getForWorkspace(fixture.workspaceId));
                assertEquals(before, connections.checkouts.get());
            });
            return null;
        });
    }

    @Test
    void deletedRootCaptureReleasesItsControlConnectionUnderEmptySynchronization() {
        String slug = "snapshot-" + UUID.randomUUID();
        fixture.controlJdbc.update("INSERT INTO workspace (org_id, name, slug) VALUES (?, 'Deleted Root', ?)",
            fixture.orgId, slug);
        int deletedId = fixture.controlJdbc.queryForObject("SELECT id FROM workspace WHERE slug = ?", Integer.class, slug);
        fixture.controlJdbc.update("DELETE FROM workspace WHERE id = ?", deletedId);
        var root = new WorkspaceLifecycleRef(deletedId, fixture.orgId, "Deleted Root", slug, "tearing_down");
        var access = new TenantLifecycleAccess(scope, catalogs, fixture.tenantContext);
        var withoutTransaction = new TransactionTemplate(transactions);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        withoutTransaction.executeWithoutResult(outer -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            org.junit.jupiter.api.Assertions.assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
            var route = access.capture(root, fixture.orgId);
            assertFalse(route.scopes().sharing().isPresent());
            assertEquals(1, connections.checkouts.get());
            assertEquals(0, fixture.pool.getHikariPoolMXBean().getActiveConnections());
            access.withRoute(route, fixture.userId, () -> {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    int before = connections.checkouts.get();
                    assertEquals(2, before);
                    assertEquals(List.of(), introductions.findCandidatePersons(deletedId, "[]"));
                    assertThrows(IllegalStateException.class, () -> sharing.getForWorkspace(deletedId));
                    assertThrows(IllegalStateException.class, () -> active.getForWorkspace(deletedId));
                    assertEquals(before, connections.checkouts.get());
                });
                return null;
            });
            assertFalse(fixture.tenantContext.isResolved());
            assertNull(fixture.tenantContext.getWorkspaceScopes());
            assertEquals(0, fixture.pool.getHikariPoolMXBean().getActiveConnections());
        });
    }

    private static List<IntroCandidatePerson> sorted(List<IntroCandidatePerson> rows) {
        return rows.stream().sorted(Comparator.comparingInt(IntroCandidatePerson::getId)).toList();
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
    static class ControlTransactions {}

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

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            checkouts.incrementAndGet();
            return super.getConnection(username, password);
        }
    }
}
