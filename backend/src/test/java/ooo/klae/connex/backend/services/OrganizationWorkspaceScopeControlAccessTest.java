package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies organization scope reads suspend and restore routed tenant transactions. */
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
    void routedControlReadSuspendsAndRestoresActiveTenantTransaction() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager transactionManager = new TestTransactionManager();
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        AtomicReference<TestTransaction> controlTransaction = new AtomicReference<>();
        when(workspaceMapper.getOrgId(7)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertNull(tenantContext.getCatalog());
            controlTransaction.set(transactionManager.currentTransaction());
            assertNotSame(outerTransaction.get(), controlTransaction.get());
            return 900;
        });
        when(workspaceMapper.findByOrgId(900)).thenAnswer(invocation -> {
            assertSame(controlTransaction.get(), transactionManager.currentTransaction());
            return List.of(workspace(7, 900), workspace(11, 900));
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
                assertEquals(List.of(7, 11), scope.workspaceIds());
                assertEquals("[7,11]", scope.workspaceIdsJson());
                assertSame(outerTransaction.get(), transactionManager.currentTransaction());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("cnx_tenant", tenantContext.getCatalog());
            });
            assertEquals(2, transactionManager.beginCount());
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
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
        return workspace;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();
        private int beginCount;

        private TestTransaction currentTransaction() {
            return current.get();
        }

        private int beginCount() {
            return beginCount;
        }

        @Override
        protected Object doGetTransaction() {
            TestTransaction transaction = current.get();
            return transaction == null ? new TestTransaction() : transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TestTransaction) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TestTransaction active = (TestTransaction) transaction;
            active.active = true;
            beginCount++;
            current.set(active);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            current.remove();
            return transaction;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            current.set((TestTransaction) suspendedResources);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            TestTransaction completed = (TestTransaction) transaction;
            completed.active = false;
            current.remove();
        }
    }

    private static final class TestTransaction {
        private boolean active;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
    }
}
