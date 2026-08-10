package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves fresh-membership entry points pin their target catalog before opening a transaction. */
@ExtendWith(MockitoExtension.class)
class FreshMembershipTransactionTest {
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void opensRealTransactionAfterTargetWorkspaceScope() {
        tenantContext.set(3, 5, 11, "owner", "cnx_previous");
        when(workspaceMapper.getOrgId(7)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return 13;
        });
        when(tenantCatalogResolver.resolveCatalog(13)).thenReturn("cnx_target");
        TestTransactionManager transactionManager = new TestTransactionManager(tenantContext);
        FreshMembershipTransaction transaction = transaction(transactionManager);

        String result = transaction.execute(7, () -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_target", tenantContext.getCatalog());
            return "joined";
        });

        assertEquals("joined", result);
        assertEquals("cnx_target", transactionManager.catalogAtBegin());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertPreviousTenantContext();
    }

    @Test
    void rejectsAProgrammaticTransactionStartedBeforeTargetWorkspaceRouting() {
        tenantContext.set(3, 5, 11, "owner", null);
        when(workspaceMapper.getOrgId(7)).thenReturn(13);
        when(tenantCatalogResolver.resolveCatalog(13)).thenReturn("cnx_target");
        TestTransactionManager transactionManager = new TestTransactionManager(tenantContext);
        FreshMembershipTransaction transaction = transaction(transactionManager);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> transaction.execute(7, () -> "joined"));
            assertEquals(
                "Catalog scope cannot CHANGE inside an active transaction: the transaction-bound "
                    + "connection keeps its original catalog, so the new pin would silently not apply. "
                    + "Re-pinning the same catalog (e.g. runAs within an already-routed span) is allowed",
                failure.getMessage());
        });

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertPreviousTenantContext(null);
    }

    @Test
    void onboardingEntrypointsHaveNoDeclarativeOuterTransaction() throws Exception {
        assertNull(FreshMembershipTransaction.class.getAnnotation(Transactional.class));
        assertNull(FreshMembershipTransaction.class
            .getMethod("execute", int.class, Supplier.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteService.class
            .getMethod("createInvite", int.class, User.class, String.class, String.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteService.class
            .getMethod("acceptInvite", String.class, User.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteService.class
            .getMethod("addExistingMember", int.class, int.class, String.class, String.class)
            .getAnnotation(Transactional.class));
        assertNull(InviteLinkService.class
            .getMethod("redeemLink", String.class, User.class)
            .getAnnotation(Transactional.class));
        assertNull(SsoLoginService.class
            .getMethod(
                "resolve",
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                int.class,
                String.class)
            .getAnnotation(Transactional.class));
    }

    private FreshMembershipTransaction transaction(TestTransactionManager transactionManager) {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        return new FreshMembershipTransaction(
            tenantWorkScope, new TransactionTemplate(transactionManager));
    }

    private void assertPreviousTenantContext() {
        assertPreviousTenantContext("cnx_previous");
    }

    private void assertPreviousTenantContext(String expectedCatalog) {
        assertTrue(tenantContext.isResolved());
        assertEquals(3, tenantContext.getWorkspaceId());
        assertEquals(5, tenantContext.getOrgId());
        assertEquals(11, tenantContext.getUserId());
        assertEquals("owner", tenantContext.getRole());
        assertEquals(expectedCatalog, tenantContext.getScopeCatalog());
        assertEquals(expectedCatalog, tenantContext.getCatalog());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();
        private final TenantContext tenantContext;
        private String catalogAtBegin;

        private TestTransactionManager(TenantContext tenantContext) {
            this.tenantContext = tenantContext;
        }

        private String catalogAtBegin() {
            return catalogAtBegin;
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
            catalogAtBegin = tenantContext.getCatalog();
            current.set(active);
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
}
