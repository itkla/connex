package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess.OrganizationWorkspaces;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Verifies the share workspace snapshot reads the control catalog, suspends and restores a routed
 * tenant transaction, fails loudly on an impossible anchor, and hydrates listings from the
 * snapshot it took. The two-catalog routing test covers the same suspend/restore against a real
 * database; these cases pin the branches it cannot reach.
 */
@ExtendWith(MockitoExtension.class)
class ShareWorkspaceControlAccessTest {

    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void nonTransactionalSnapshotRunsDirectlyInTheUnroutedScope() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return List.of(workspace(11, 900, "Alpha"), workspace(7, 900, "Owner"));
        });

        assertEquals("[11,7]", controlAccess.getForWorkspace(7).workspaceIdsJson());

        assertEquals("cnx_tenant", tenantContext.getCatalog());
        assertEquals(0, transactionManager.beginCount());
        assertEquals(0, transactionManager.suspendCount());
    }

    @Test
    void routedSnapshotSuspendsAndRestoresTheActiveTenantTransaction() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(transactionManager.currentTransaction());
            assertNull(tenantContext.getCatalog());
            return List.of(workspace(7, 900, "Owner"), workspace(11, 900, "Zulu"));
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerTransaction.set(transactionManager.currentTransaction());

            assertEquals("[7,11]", controlAccess.getForWorkspace(7).workspaceIdsJson());

            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(outerTransaction.get(), transactionManager.currentTransaction());
            assertEquals("cnx_tenant", tenantContext.getCatalog());
        });

        assertEquals(1, transactionManager.suspendCount());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void defaultCatalogSnapshotStaysInTheCallerTransaction() {
        tenantContext.set(7, 900, 42, "member", null);
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(outerTransaction.get(), transactionManager.currentTransaction());
            assertNull(tenantContext.getCatalog());
            return List.of(workspace(7, 900, "Owner"));
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerTransaction.set(transactionManager.currentTransaction());

            assertEquals("[7]", controlAccess.getForWorkspace(7).workspaceIdsJson());
        });

        assertEquals(1, transactionManager.beginCount());
        assertEquals(0, transactionManager.suspendCount());
    }

    /**
     * An anchor workspace that does not exist, or that the snapshot statement does not return,
     * must fail loudly rather than degrade into an empty allowlist that reads as an ordinary
     * permission refusal.
     */
    @Test
    void impossibleAnchorFailsLoudlyInsteadOfYieldingAnEmptyAllowlist() {
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenReturn(List.of());
        when(workspaceMapper.findOrganizationWorkspacesForShare(11))
            .thenReturn(List.of(workspace(12, 900, "Peer")));

        assertEquals("Workspace 7 does not exist",
            assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(7))
                .getMessage());
        assertEquals("Workspace 11 is missing from organization 900",
            assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(11))
                .getMessage());
    }

    @Test
    void resolvedTenantMustMatchTheSnapshotWorkspaceAndOrganization() {
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        when(workspaceMapper.findOrganizationWorkspacesForShare(7))
            .thenReturn(List.of(workspace(7, 900, "Owner")));

        tenantContext.set(11, 900, 42, "member", "cnx_tenant");
        assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(7));

        tenantContext.set(7, 901, 42, "member", "cnx_tenant");
        assertThrows(IllegalStateException.class, () -> controlAccess.getForWorkspace(7));

        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        assertEquals("[7]", controlAccess.getForWorkspace(7).workspaceIdsJson());
    }

    @Test
    void hydrationNamesRowsInSnapshotOrderAndDropsTargetsOutsideIt() {
        TestTransactionManager transactionManager = new TestTransactionManager();
        ShareWorkspaceControlAccess controlAccess = controlAccess(transactionManager);
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenReturn(
            List.of(workspace(11, 900, "Alpha"), workspace(7, 900, "Owner"),
                workspace(9, 900, "Zulu")));
        OrganizationWorkspaces organizationWorkspaces = controlAccess.getForWorkspace(7);

        List<ShareDto> hydrated = organizationWorkspaces.hydrate(
            List.of(share(9), share(404), share(11)));

        assertEquals(List.of(11, 9), hydrated.stream().map(ShareDto::getWorkspaceId).toList());
        assertEquals(List.of("Alpha", "Zulu"),
            hydrated.stream().map(ShareDto::getWorkspaceName).toList());
    }

    private ShareWorkspaceControlAccess controlAccess(TestTransactionManager transactionManager) {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        return new ShareWorkspaceControlAccess(
            workspaceMapper, tenantWorkScope, tenantContext, transactionManager);
    }

    private static Workspace workspace(int id, int orgId, String name) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrgId(orgId);
        workspace.setName(name);
        return workspace;
    }

    private static ShareDto share(int workspaceId) {
        ShareDto share = new ShareDto();
        share.setWorkspaceId(workspaceId);
        return share;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();
        private int beginCount;
        private int suspendCount;

        private TestTransaction currentTransaction() {
            return current.get();
        }

        private int beginCount() {
            return beginCount;
        }

        private int suspendCount() {
            return suspendCount;
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
            suspendCount++;
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
}
