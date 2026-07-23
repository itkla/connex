package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

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

import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.dto.UserProfileHydrationRow;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies control-profile hydration suspends and restores routed tenant transactions. */
@ExtendWith(MockitoExtension.class)
class DealCollaboratorControlAccessTest {
    @Mock private UserMapper userMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper workspaceMapper;

    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void routedControlReadSuspendsAndRestoresAnActiveTenantTransaction() {
        tenantContext.set(7, 8, 42, "member", "cnx_tenant");
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        TestTransactionManager transactionManager = new TestTransactionManager();
        DealCollaboratorControlAccess controlAccess = new DealCollaboratorControlAccess(
            userMapper, tenantWorkScope, tenantContext, transactionManager);
        UserDto profile = profile(11, "Alpha");
        when(userMapper.getWorkspaceProfileHydrationRowsByIds(7, List.of(11))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return List.of(row(profile, 1));
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_tenant", tenantContext.getCatalog());

            assertEquals(List.of(profile), controlAccess.getProfiles(7, List.of(11)));

            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("cnx_tenant", tenantContext.getCatalog());
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void profileReadsAreBatchedAndGloballyOrdered() {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        DealCollaboratorControlAccess controlAccess = new DealCollaboratorControlAccess(
            userMapper, tenantWorkScope, tenantContext, new TestTransactionManager());
        List<Integer> ids = IntStream.rangeClosed(1, 501).boxed().toList();
        UserDto eclair = profile(1, "Éclair");
        UserDto zulu = profile(501, "Zulu");
        when(userMapper.getWorkspaceProfileHydrationRowsByIds(7, ids.subList(0, 500)))
            .thenReturn(List.of(row(eclair, 1)));
        when(userMapper.getWorkspaceProfileHydrationRowsByIds(7, ids.subList(500, 501)))
            .thenReturn(List.of(row(zulu, 2)));

        assertEquals(List.of(eclair, zulu), controlAccess.getProfiles(7, ids));
    }

    @Test
    void equalCollationWeightsUseUserIdAsTieBreaker() {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        DealCollaboratorControlAccess controlAccess = new DealCollaboratorControlAccess(
            userMapper, tenantWorkScope, tenantContext, new TestTransactionManager());
        UserDto second = profile(12, "Alpha");
        UserDto first = profile(11, "alpha");
        when(userMapper.getWorkspaceProfileHydrationRowsByIds(7, List.of(12, 11)))
            .thenReturn(List.of(row(second, 1), row(first, 1)));

        assertEquals(List.of(first, second), controlAccess.getProfiles(7, List.of(12, 11)));
    }

    private static UserDto profile(int id, String displayName) {
        UserDto profile = new UserDto();
        profile.setId(id);
        profile.setDisplayName(displayName);
        return profile;
    }

    private static UserProfileHydrationRow row(UserDto profile, int sortKey) {
        UserProfileHydrationRow row = new UserProfileHydrationRow();
        row.setId(profile.getId());
        row.setProfile(profile);
        row.setDisplaySortKey(new byte[] {(byte) sortKey});
        return row;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<TestTransaction> current = new ThreadLocal<>();

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
}
