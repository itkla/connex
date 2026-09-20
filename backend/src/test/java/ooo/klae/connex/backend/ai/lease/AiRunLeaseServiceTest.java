package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;

/**
 * Unit coverage of the lease service's claim, renew, release, and takeover decisions.
 *
 * <p>Transaction synchronization is initialized for every test because the service binds its
 * JVM-local token state to transaction completion; {@link #commit()} and {@link #rollBack()} drive
 * those callbacks the way a real commit or rollback would.
 */
class AiRunLeaseServiceTest {

    private static final AiRunLeaseKey KEY =
            new AiRunLeaseKey(7, AiRunLeaseSubject.CHAT_TURN, 42L);
    private static final String CHAT_TURN = "chat_turn";

    private final AiRunLeaseMapper leaseMapper = mock(AiRunLeaseMapper.class);
    private final AiRunLeaseIdentity identity = new AiRunLeaseIdentity();
    private final AiRunLeaseRegistry registry = new AiRunLeaseRegistry();
    private final AiProperties properties = new AiProperties();
    private final AiRunLeaseService service =
            new AiRunLeaseService(leaseMapper, identity, registry, properties);

    @BeforeEach
    void openTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void closeTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void commit() {
        complete(TransactionSynchronization.STATUS_COMMITTED);
    }

    private void rollBack() {
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    private void complete(int status) {
        List<TransactionSynchronization> synchronizations =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }

    @Test
    void firstClaimOfASubjectInsertsAtEpochOneAndRegistersTheToken() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);

        AiRunLease lease = service.acquireInCurrentTransaction(KEY);

        assertEquals(1L, lease.epoch());
        assertEquals(identity.owner(), lease.owner());
        assertEquals(KEY, lease.key());
        assertEquals(Optional.of(lease), registry.find(KEY));
        verify(leaseMapper, never()).takeOver(anyInt(), anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void claimingATombstonedOrExpiredRowTakesItOverAtTheNextEpoch() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(row(4L));
        when(leaseMapper.takeOver(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);

        AiRunLease lease = service.acquireInCurrentTransaction(KEY);

        assertEquals(5L, lease.epoch());
        verify(leaseMapper, never()).insert(anyInt(), anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void claimingALiveHeldLeaseIsRefusedAndRegistersNothing() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(row(2L));
        when(leaseMapper.takeOver(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.acquireInCurrentTransaction(KEY));

        assertTrue(registry.find(KEY).isEmpty());
    }

    @Test
    void renewBindsTheHolderTokenAndReportsOnlyAnAuthoritativeLoss() {
        AiRunLease lease = new AiRunLease(KEY, "owner-a", 3L);
        when(leaseMapper.renew(7, CHAT_TURN, 42L, "owner-a", 3L, 45)).thenReturn(1, 0);

        assertEquals(AiRunLeaseOutcome.HELD, service.renew(lease));
        assertEquals(AiRunLeaseOutcome.LOST, service.renew(lease));

        verify(leaseMapper, times(2)).renew(7, CHAT_TURN, 42L, "owner-a", 3L, 45);
    }

    @Test
    void aRenewalThatCannotReachTheDatabaseThrowsRatherThanReportingLoss() {
        AiRunLease lease = new AiRunLease(KEY, "owner-a", 3L);
        IllegalStateException failure = new IllegalStateException("connection reset");
        when(leaseMapper.renew(7, CHAT_TURN, 42L, "owner-a", 3L, 45)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.renew(lease)));
    }

    @Test
    void releaseTombstonesWithTheRegisteredTokenAndThenForgetsIt() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);
        service.acquireInCurrentTransaction(KEY);
        commit();
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L)).thenReturn(1);

        assertTrue(service.releaseHeldInCurrentTransaction(KEY));
        commit();

        assertTrue(registry.find(KEY).isEmpty());
        verify(leaseMapper).tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L);
        verify(leaseMapper, never()).retire(anyInt(), anyString(), anyLong());
    }

    /**
     * The token is JVM-local, so a terminal write that lands anywhere but the claiming instance
     * has none to fence with. Skipping the release there would leave the row held and unreleased
     * for good: the reap deletes tombstones only, so nothing in this design would ever retire it.
     */
    @Test
    void releasingASubjectThisInstanceHoldsNoTokenForRetiresTheRowByKey() {
        when(leaseMapper.retire(7, CHAT_TURN, 42L)).thenReturn(1);

        assertTrue(service.releaseHeldInCurrentTransaction(KEY));

        verify(leaseMapper).retire(7, CHAT_TURN, 42L);
        verify(leaseMapper, never())
                .tombstone(anyInt(), anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    void retiringARowThatWasAlreadyReleasedReportsNoRelease() {
        when(leaseMapper.retire(7, CHAT_TURN, 42L)).thenReturn(0);

        assertFalse(service.releaseHeldInCurrentTransaction(KEY));
    }

    /**
     * A worker drops its token when it stops working, so a run whose terminal write never lands
     * cannot leave the token behind for the life of the process. Dropping it must never touch the
     * lease row: the run's lease has to stay held and expiring for a settler to find.
     */
    @Test
    void forgettingALocalTokenReleasesNothingAndEmptiesTheRegistry() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);
        AiRunLease claimed = service.acquireInCurrentTransaction(KEY);
        commit();

        service.forgetLocalToken(claimed);

        assertTrue(registry.find(KEY).isEmpty());
        verify(leaseMapper, never())
                .tombstone(anyInt(), anyString(), anyLong(), anyString(), anyLong());
        verify(leaseMapper, never()).retire(anyInt(), anyString(), anyLong());
    }

    @Test
    void forgettingNoTokenAtAllIsAcceptedSoARefusedClaimNeedsNoBranch() {
        service.forgetLocalToken(null);

        verifyNoMoreInteractions(leaseMapper);
    }

    /**
     * A claim that never commits must leave nothing behind: the row reverts, so a retained token
     * would be a permanent entry in a map nothing ever prunes and a fence this instance no longer
     * holds.
     */
    @Test
    void aClaimThatRollsBackForgetsItsTokenAgain() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);

        AiRunLease lease = service.acquireInCurrentTransaction(KEY);
        assertEquals(Optional.of(lease), registry.find(KEY));
        rollBack();

        assertTrue(registry.find(KEY).isEmpty());
    }

    /**
     * A terminal transaction that rolls back leaves the row held, so the token has to survive for
     * the retry. Forgetting it inline would strand a lease the database still shows as held and
     * let a settler take a cleanly finished run over as if its owner had died.
     */
    @Test
    void aReleaseThatRollsBackKeepsTheTokenForTheRetry() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);
        AiRunLease lease = service.acquireInCurrentTransaction(KEY);
        commit();
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L)).thenReturn(1);

        assertTrue(service.releaseHeldInCurrentTransaction(KEY));
        rollBack();

        assertEquals(Optional.of(lease), registry.find(KEY));
    }

    /**
     * An absent primary key takes no lock at READ COMMITTED, so two first claimants can both reach
     * the insert. The loser must learn it lost in the vocabulary its caller understands rather than
     * through a raw duplicate-key or deadlock failure no contract describes.
     */
    @Test
    void losingTheRaceToInsertAFirstClaimIsReportedAsAConflict() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key ai_run_lease.PRIMARY"))
                .thenThrow(new DeadlockLoserDataAccessException("Deadlock found", null));

        assertThrows(ConflictException.class, () -> service.acquireInCurrentTransaction(KEY));
        assertThrows(ConflictException.class, () -> service.acquireInCurrentTransaction(KEY));

        assertTrue(registry.find(KEY).isEmpty());
    }

    /**
     * A lease lifetime that is not a whole number of seconds cannot be expressed by the database's
     * {@code INTERVAL n SECOND} deadline, and truncating it would mint a lease that is already
     * expired when it is written.
     */
    @Test
    void aSubSecondLeaseLifetimeIsRefusedRatherThanTruncated() {
        properties.setRunLeaseTtl(Duration.ofMillis(900));

        assertThrows(
                IllegalArgumentException.class, () -> service.acquireInCurrentTransaction(KEY));

        verify(leaseMapper, never()).insert(anyInt(), anyString(), anyLong(), anyString(), anyInt());
    }

    @Test
    void releaseReportsFalseWhenTheHeldTokenNoLongerMatchesTheRow() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);
        service.acquireInCurrentTransaction(KEY);
        commit();
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L)).thenReturn(0);

        assertFalse(service.releaseHeldInCurrentTransaction(KEY));
        commit();

        assertTrue(registry.find(KEY).isEmpty());
    }

    @Test
    void settlementTakeoverIsFencedOnTheObservedEpochAndUsesTheSettlementLifetime() {
        when(leaseMapper.takeOverForSettlement(7, CHAT_TURN, 42L, identity.owner(), 6L, 30))
                .thenReturn(1);

        Optional<AiRunLease> takeover = service.takeOverForSettlement(KEY, 6L);

        assertTrue(takeover.isPresent());
        assertEquals(7L, takeover.get().epoch());
        assertEquals(identity.owner(), takeover.get().owner());
        assertEquals(takeover, registry.find(KEY));
    }

    /**
     * The settler releases its takeover through the same registry-backed entry point the owner
     * uses, so an unregistered takeover token would make that release a silent no-op and leave a
     * held settlement lease for the next sweep pass to rediscover on every pass.
     */
    @Test
    void aSettlementTakeoverCanBeReleasedThroughTheSameEntryPointAnOwnerUses() {
        when(leaseMapper.takeOverForSettlement(7, CHAT_TURN, 42L, identity.owner(), 6L, 30))
                .thenReturn(1);
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 7L)).thenReturn(1);
        service.takeOverForSettlement(KEY, 6L);
        commit();

        assertTrue(service.releaseHeldInCurrentTransaction(KEY));
        commit();

        verify(leaseMapper).tombstone(7, CHAT_TURN, 42L, identity.owner(), 7L);
        assertTrue(registry.find(KEY).isEmpty());
    }

    @Test
    void settlementTakeoverYieldsNothingWhenTheLeaseMovedOnFirst() {
        when(leaseMapper.takeOverForSettlement(7, CHAT_TURN, 42L, identity.owner(), 6L, 30))
                .thenReturn(0);

        assertTrue(service.takeOverForSettlement(KEY, 6L).isEmpty());
    }

    /**
     * A delete restarts the key's fencing epoch at 1, so the reap may only touch subject kinds
     * whose runs are provably shorter than the validated retention window.
     */
    @Test
    void reapDelegatesTheRetentionWindowAndDeletesOnlyReapableSubjectKinds() {
        List<String> reapable = List.of(CHAT_TURN);
        when(leaseMapper.deleteTombstones(7, reapable, 3600, 50)).thenReturn(4);

        assertEquals(4, service.reapTombstones(7, 3600, 50));

        verify(leaseMapper).deleteTombstones(eq(7), eq(reapable), eq(3600), eq(50));
        assertFalse(AiRunLeaseSubject.AGENT_RUN.isTombstoneReapable());
    }

    private static AiRunLeaseRow row(long epoch) {
        AiRunLeaseRow row = new AiRunLeaseRow();
        row.setWorkspaceId(7);
        row.setSubjectKind(CHAT_TURN);
        row.setSubjectId(42L);
        row.setOwner("owner-b");
        row.setEpoch(epoch);
        return row;
    }
}
