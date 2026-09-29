package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

/** Verifies collaborator profile hydration routes to the control catalog and keeps display order. */
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
    void nonTransactionalHydrationRunsDirectlyInTheUnroutedScope() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TestTransactionManager transactionManager = new TestTransactionManager();
        DealCollaboratorControlAccess controlAccess = controlAccess(transactionManager);
        UserDto profile = profile(11);
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, List.of(11))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return List.of(row(profile, 0x1C));
        });

        assertEquals(List.of(profile), controlAccess.getProfiles(7, List.of(11)));

        assertEquals("cnx_tenant", tenantContext.getCatalog());
        assertEquals(0, transactionManager.beginCount());
        assertEquals(0, transactionManager.suspendCount());
    }

    @Test
    void routedHydrationSuspendsAndRestoresTheActiveTenantTransaction() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TestTransactionManager transactionManager = new TestTransactionManager();
        DealCollaboratorControlAccess controlAccess = controlAccess(transactionManager);
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        UserDto profile = profile(11);
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, List.of(11))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(transactionManager.currentTransaction());
            assertNull(tenantContext.getCatalog());
            return List.of(row(profile, 0x1C));
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerTransaction.set(transactionManager.currentTransaction());

            assertEquals(List.of(profile), controlAccess.getProfiles(7, List.of(11)));

            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(outerTransaction.get(), transactionManager.currentTransaction());
            assertEquals("cnx_tenant", tenantContext.getCatalog());
        });

        assertEquals(1, transactionManager.suspendCount());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("cnx_tenant", tenantContext.getCatalog());
    }

    @Test
    void defaultCatalogHydrationStaysInTheCallerTransaction() {
        tenantContext.set(7, 900, 42, "member", null);
        TestTransactionManager transactionManager = new TestTransactionManager();
        DealCollaboratorControlAccess controlAccess = controlAccess(transactionManager);
        AtomicReference<TestTransaction> outerTransaction = new AtomicReference<>();
        UserDto profile = profile(11);
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, List.of(11))).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertSame(outerTransaction.get(), transactionManager.currentTransaction());
            assertNull(tenantContext.getCatalog());
            return List.of(row(profile, 0x1C));
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outerTransaction.set(transactionManager.currentTransaction());

            assertEquals(List.of(profile), controlAccess.getProfiles(7, List.of(11)));
        });

        assertEquals(1, transactionManager.beginCount());
        assertEquals(0, transactionManager.suspendCount());
    }

    @Test
    void emptyOrMissingIdsSkipTheControlLookup() {
        tenantContext.set(7, 900, 42, "member", "cnx_tenant");
        TestTransactionManager transactionManager = new TestTransactionManager();
        DealCollaboratorControlAccess controlAccess = controlAccess(transactionManager);

        assertEquals(List.of(), controlAccess.getProfiles(7, List.of()));
        assertEquals(List.of(), controlAccess.getProfiles(7, null));
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> assertEquals(List.of(), controlAccess.getProfiles(7, List.of())));

        verifyNoInteractions(userMapper);
        assertEquals(1, transactionManager.beginCount());
        assertEquals(0, transactionManager.suspendCount());
    }

    @Test
    void hydrationBatchesIdsAndMergesBatchesByUnsignedCollationKeyThenId() {
        DealCollaboratorControlAccess controlAccess = controlAccess(new TestTransactionManager());
        List<Integer> ids = IntStream.rangeClosed(1, 2 * DealCollaboratorControlAccess.PROFILE_BATCH_SIZE + 1)
            .boxed()
            .toList();
        UserDto latinTwin = profile(2);
        UserDto japanese = profile(3);
        UserDto latinTwinLater = profile(501);
        UserDto prefix = profile(600);
        UserDto lastBatch = profile(1001);
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, ids.subList(0, 500)))
            .thenReturn(List.of(row(latinTwin, 0x1C, 0x47), row(japanese, 0xFB, 0x40)));
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, ids.subList(500, 1000)))
            .thenReturn(List.of(row(prefix, 0x1C), row(latinTwinLater, 0x1C, 0x47)));
        when(userMapper.getActiveWorkspaceMemberProfilesByIds(7, ids.subList(1000, 1001)))
            .thenReturn(List.of(row(lastBatch, 0x7F)));

        List<UserDto> profiles = controlAccess.getProfiles(7, ids);

        assertEquals(List.of(prefix, latinTwin, latinTwinLater, lastBatch, japanese), profiles);
        verify(userMapper).getActiveWorkspaceMemberProfilesByIds(7, ids.subList(0, 500));
        verify(userMapper).getActiveWorkspaceMemberProfilesByIds(7, ids.subList(500, 1000));
        verify(userMapper).getActiveWorkspaceMemberProfilesByIds(7, ids.subList(1000, 1001));
    }

    private DealCollaboratorControlAccess controlAccess(TestTransactionManager transactionManager) {
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, tenantCatalogResolver, workspaceMapper);
        return new DealCollaboratorControlAccess(userMapper, tenantWorkScope, tenantContext, transactionManager);
    }

    private static UserDto profile(int id) {
        UserDto profile = new UserDto();
        profile.setId(id);
        profile.setDisplayName("Collaborator " + id);
        return profile;
    }

    private static UserProfileHydrationRow row(UserDto profile, int... sortKey) {
        byte[] key = new byte[sortKey.length];
        for (int index = 0; index < sortKey.length; index++) {
            key[index] = (byte) sortKey[index];
        }
        UserProfileHydrationRow row = new UserProfileHydrationRow();
        row.setId(profile.getId());
        row.setDisplaySortKey(key);
        row.setProfile(profile);
        return row;
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
