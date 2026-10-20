package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.support.TestTransactionManager.TestTransaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.List;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies prepared routed reads never borrow and default-mode transaction behavior is unchanged. */
@ExtendWith(MockitoExtension.class)
class OrganizationWorkspaceScopeControlAccessTest {
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void nonTransactionalControlReadUsesOneReadOnlySnapshot() {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager transactionManager = new TestTransactionManager();
        AtomicReference<TestTransaction> snapshot = new AtomicReference<>();
        when(workspaceMapper.getOrgId(7)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertNull(tenantContext.getCatalog());
            snapshot.set(transactionManager.currentTransaction());
            return 900;
        });
        when(workspaceMapper.findByOrgId(900)).thenAnswer(invocation -> {
            assertSame(snapshot.get(), transactionManager.currentTransaction());
            return List.of(workspace(11, 900), workspace(7, 900));
        });

        try (AnnotationConfigApplicationContext context = controlContext(transactionManager)) {
            OrganizationWorkspaceScopeControlAccess controlAccess =
                new OrganizationWorkspaceScopeControlAccess(
                    context.getBean(OrganizationWorkspaceScopeControlOperations.class),
                    tenantWorkScope, tenantContext, transactionManager);

            WorkspaceScope scope = controlAccess.getForWorkspace(7);

            assertEquals(900, scope.orgId());
            assertEquals(List.of(7, 11), scope.workspaceIds());
            assertEquals("[7,11]", scope.workspaceIdsJson());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, transactionManager.beginCount());
        }
    }

    @Test
    void routedReadsReuseSnapshotsWithoutExtraCheckoutsAndRejectMissingOrMismatchedScopes() throws Exception {
        when(tenantCatalogResolver.isCatalogPerPlacement()).thenReturn(true);
        when(tenantCatalogResolver.resolveCatalog(900)).thenReturn("cnx_tenant");
        when(workspaceMapper.getOrgId(7)).thenReturn(900);
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenReturn(
            List.of(workspace(7, 900), workspace(11, 900)));
        when(workspaceMapper.findByOrgId(900)).thenReturn(List.of(workspace(7, 900), workspace(11, 900)));
        TenantWorkScope workScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper,
            mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
            new TenantWorkspaceScopeControlOperations(workspaceMapper, tenantCatalogResolver));
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        AtomicInteger checkouts = new AtomicInteger();
        when(dataSource.getConnection()).thenAnswer(call -> {
            checkouts.incrementAndGet();
            return connection;
        });
        when(connection.getAutoCommit()).thenReturn(true);
        var manager = new DataSourceTransactionManager(dataSource);
        var controlOperations = mock(OrganizationWorkspaceScopeControlOperations.class);
        var active = new OrganizationWorkspaceScopeControlAccess(controlOperations, workScope, tenantContext, manager);
        var sharing = new ShareWorkspaceControlAccess(workspaceMapper, workScope, tenantContext, manager);
        var visibility = new RecordVisibilityScopeControlAccess(sharing);

        workScope.inWorkspace(7, () -> {
            tenantContext.set(7, 900, 42, "member", "cnx_tenant");
            var prepared = tenantContext.getWorkspaceScopes();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                int before = checkouts.get();
                assertEquals(1, before);
                assertSame(connection, DataSourceUtils.getConnection(dataSource));
                assertSame(prepared.active(), active.getForWorkspace(7));
                assertSame(prepared.active(), active.getForOrg(900));
                assertSame(prepared.requireSharing(), sharing.getForWorkspace(7));
                assertSame(visibility.getForWorkspace(7), visibility.getForWorkspace(7));
                assertEquals("[7,11]", active.getForWorkspace(7).workspaceIdsJson());
                assertEquals(List.of(7, 11), visibility.getForWorkspace(7).workspaceIds());
                assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForWorkspace(11));
                assertThrows(TenantScopeSnapshotMissingException.class, () -> sharing.getForWorkspace(11));
                assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForOrg(901));
                TenantContext.State previous = tenantContext.snapshot();
                tenantContext.set(7, 901, 42, "member", "cnx_tenant");
                assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForWorkspace(7));
                assertThrows(TenantScopeSnapshotMissingException.class, () -> visibility.getForWorkspace(7));
                tenantContext.clear();
                tenantContext.set(7, 900, 42, "member", "cnx_tenant");
                assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForWorkspace(7));
                assertThrows(TenantScopeSnapshotMissingException.class, () -> sharing.getForWorkspace(7));
                tenantContext.restore(previous);
                assertSame(connection, DataSourceUtils.getConnection(dataSource));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("cnx_tenant", tenantContext.getCatalog());
                assertEquals(before, checkouts.get());
            });
            return null;
        });
        assertEquals(1, checkouts.get());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
        verifyNoInteractions(controlOperations);
    }

    @Test
    void defaultCatalogControlReadDoesNotJoinTheCallerTransaction() {
        tenantContext.set(7, 900, 42, "member", null);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager transactionManager = new TestTransactionManager();
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        AtomicReference<TestTransaction> controlTransaction = new AtomicReference<>();
        when(workspaceMapper.getOrgId(7)).thenAnswer(invocation -> {
            controlTransaction.set(transactionManager.currentTransaction());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertNotSame(outerTransaction.get(), controlTransaction.get());
            return 900;
        });
        when(workspaceMapper.findByOrgId(900)).thenAnswer(invocation -> {
            assertSame(controlTransaction.get(), transactionManager.currentTransaction());
            return List.of(workspace(7, 900));
        });

        try (AnnotationConfigApplicationContext context = controlContext(transactionManager)) {
            OrganizationWorkspaceScopeControlAccess controlAccess =
                new OrganizationWorkspaceScopeControlAccess(
                    context.getBean(OrganizationWorkspaceScopeControlOperations.class),
                    tenantWorkScope, tenantContext, transactionManager);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                outerTransaction.set(transactionManager.currentTransaction());

                WorkspaceScope scope = controlAccess.getForWorkspace(7);

                assertEquals(900, scope.orgId());
                assertSame(outerTransaction.get(), transactionManager.currentTransaction());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertNull(tenantContext.getCatalog());
            });
            assertEquals(2, transactionManager.beginCount());
        }
    }

    @Test
    void singleDatabasePreparationIsInertAndRepeatedBoundaryCallsRetainTheirLazyQueryCounts() {
        TenantWorkScope workScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager manager = new TestTransactionManager();
        when(workspaceMapper.getOrgId(7)).thenReturn(900);
        when(workspaceMapper.findByOrgId(900)).thenReturn(List.of(workspace(7, 900), workspace(11, 900)));
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenReturn(
            List.of(workspace(11, 900), workspace(7, 900)));
        assertNull(workScope.prepareWorkspaceScopes(7, 900, null));
        verifyNoInteractions(workspaceMapper);
        var sharing = new ShareWorkspaceControlAccess(workspaceMapper, workScope, tenantContext, manager);
        var visibility = new RecordVisibilityScopeControlAccess(sharing);
        try (AnnotationConfigApplicationContext context = controlContext(manager)) {
            var active = new OrganizationWorkspaceScopeControlAccess(
                context.getBean(OrganizationWorkspaceScopeControlOperations.class), workScope, tenantContext, manager);
            for (int repeat = 0; repeat < 2; repeat++) {
                assertEquals("[7,11]", active.getForWorkspace(7).workspaceIdsJson());
                assertEquals("[11,7]", sharing.getForWorkspace(7).workspaceIdsJson());
                assertEquals("[7,11]", visibility.getForWorkspace(7).workspaceIdsJson());
                assertNull(tenantContext.getWorkspaceScopes());
            }
            assertEquals(2, manager.beginCount());
        }
        verify(workspaceMapper, times(2)).getOrgId(7);
        verify(workspaceMapper, times(2)).findByOrgId(900);
        verify(workspaceMapper, times(4)).findOrganizationWorkspacesForShare(7);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "cnx_tenant")
    void routedMissingScopesFailBeforeControlAccessEvenForSharedPlacements(String catalog) {
        when(tenantCatalogResolver.isCatalogPerPlacement()).thenReturn(true);
        tenantContext.set(7, 900, 42, "member", catalog);
        var workScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper,
            mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
            mock(TenantWorkspaceScopeControlOperations.class));
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var operations = mock(OrganizationWorkspaceScopeControlOperations.class);
        var active = new OrganizationWorkspaceScopeControlAccess(operations, workScope, tenantContext, manager);
        var sharing = new ShareWorkspaceControlAccess(workspaceMapper, workScope, tenantContext, manager);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForWorkspace(7));
        assertThrows(TenantScopeSnapshotMissingException.class, () -> active.getForOrg(900));
        assertThrows(TenantScopeSnapshotMissingException.class,
            () -> new RecordVisibilityScopeControlAccess(sharing).getForWorkspace(7));
        verifyNoInteractions(workspaceMapper, manager, operations);
    }

    @Test
    void resolvedTenantMustMatchWorkspaceAndOrganizationScope() {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager transactionManager = new TestTransactionManager();
        when(workspaceMapper.getOrgId(7)).thenReturn(900);
        when(workspaceMapper.findByOrgId(900)).thenReturn(List.of(workspace(7, 900)));

        try (AnnotationConfigApplicationContext context = controlContext(transactionManager)) {
            OrganizationWorkspaceScopeControlAccess controlAccess =
                new OrganizationWorkspaceScopeControlAccess(
                    context.getBean(OrganizationWorkspaceScopeControlOperations.class),
                    tenantWorkScope, tenantContext, transactionManager);

            tenantContext.set(11, 900, 42, "member", "cnx_tenant");
            assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(7));

            tenantContext.set(7, 901, 42, "member", "cnx_tenant");
            assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(7));
            assertThrows(ResourceNotFoundException.class, () -> controlAccess.getForOrg(900));
        }
    }

    private AnnotationConfigApplicationContext controlContext(TestTransactionManager transactionManager) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean("transactionManager", TestTransactionManager.class, () -> transactionManager);
        context.registerBean(OrganizationWorkspaceScopeControlOperations.class,
            () -> new OrganizationWorkspaceScopeControlOperations(workspaceMapper));
        context.refresh();
        return context;
    }

    private static Workspace workspace(int id, int orgId) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrgId(orgId);
        workspace.setName("Workspace " + id);
        return workspace;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
    }
}
