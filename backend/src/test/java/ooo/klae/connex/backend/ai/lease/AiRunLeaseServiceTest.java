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

import java.util.Optional;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;

/** Unit coverage of the lease service's claim, renew, release, and takeover decisions. */
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
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L)).thenReturn(1);

        assertTrue(service.releaseHeldInCurrentTransaction(KEY));

        assertTrue(registry.find(KEY).isEmpty());
        verify(leaseMapper).tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L);
    }

    @Test
    void releasingAKeyThisInstanceHoldsNoTokenForTouchesNothing() {
        assertFalse(service.releaseHeldInCurrentTransaction(KEY));

        verify(leaseMapper, never()).tombstone(anyInt(), anyString(), anyLong(), anyString(), anyLong());
        verifyNoMoreInteractions(leaseMapper);
    }

    @Test
    void releaseReportsFalseWhenTheHeldTokenNoLongerMatchesTheRow() {
        when(leaseMapper.lockForUpdate(7, CHAT_TURN, 42L)).thenReturn(null);
        when(leaseMapper.insert(7, CHAT_TURN, 42L, identity.owner(), 45)).thenReturn(1);
        service.acquireInCurrentTransaction(KEY);
        when(leaseMapper.tombstone(7, CHAT_TURN, 42L, identity.owner(), 1L)).thenReturn(0);

        assertFalse(service.releaseHeldInCurrentTransaction(KEY));
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
    }

    @Test
    void settlementTakeoverYieldsNothingWhenTheLeaseMovedOnFirst() {
        when(leaseMapper.takeOverForSettlement(7, CHAT_TURN, 42L, identity.owner(), 6L, 30))
                .thenReturn(0);

        assertTrue(service.takeOverForSettlement(KEY, 6L).isEmpty());
    }

    @Test
    void reapDelegatesTheRetentionWindowAndBatchLimit() {
        when(leaseMapper.deleteTombstones(7, 3600, 50)).thenReturn(4);

        assertEquals(4, service.reapTombstones(7, 3600, 50));

        verify(leaseMapper).deleteTombstones(eq(7), eq(3600), eq(50));
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
