package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.exceptions.AiPrivacyInitializationPendingException;
import ooo.klae.connex.backend.mappers.AiChatMapper;

class AiChatPrivacyGuardTest {
    private final AiAssistantPrivacyService authority = mock(AiAssistantPrivacyService.class);
    private final AiChatPrivacyGuard guard = new AiChatPrivacyGuard(authority);
    private final AiChatMapper mapper = mock(AiChatMapper.class);
    private final AiChatSession session = new AiChatSession();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();

    @BeforeEach
    void setUp() {
        session.setWorkspaceId(7);
        session.setOrganizationPrivacyGeneration(1L);
        when(authority.organizationId(7)).thenReturn(1);
        when(authority.current(1)).thenReturn(1L);
        when(authority.freshCurrent(1)).thenReturn(1L);
        session.setId(13);
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(mapper.getPrivacySession(7, 13)).thenReturn(session);
        when(mapper.getPrivacyBarrier(7)).thenReturn(barrier);
        when(mapper.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
    }

    @Test
    void preTaintDerivedJobCannotPublishAfterTaint() {
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        session.setMissionTaintSeq(9);
        session.setMissionTaintGeneration(4L);

        assertStale(() -> guard.requireCurrent(mapper, captured));
        assertStale(() -> guard.retain(mapper, captured));
    }

    @Test
    void staleGenerationRemainsRejectedAfterPurgeCompletesAndUnblocks() {
        session.setMissionTaintSeq(9);
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        barrier.setGeneration(5);
        barrier.setBlocked(false);

        assertStale(() -> guard.requireCurrent(mapper, captured));
        assertStale(() -> guard.retain(mapper, captured));
        assertEquals(4, captured.privacyGeneration());
    }

    @Test
    void blockedTaintedSessionRefusesBothAdmissionAndExistingExecution() {
        session.setMissionTaintSeq(9);
        barrier.setBlocked(true);

        assertStale(() -> guard.capture(mapper, session));
        assertStale(() -> guard.requireCurrent(mapper, new AiChatPrivacyToken(7, 13, 4, 9, 1, 1L)));
    }

    @Test
    void unrelatedUntaintedSessionRemainsUsableDuringCleanup() {
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        barrier.setGeneration(5);
        barrier.setBlocked(true);

        assertDoesNotThrow(() -> guard.requireCurrent(mapper, captured));
        assertDoesNotThrow(() -> guard.retain(mapper, captured));
    }

    @Test
    void missingBarrierAndLegacyExecutionTokenFailClosed() {
        when(mapper.getPrivacyBarrier(7)).thenReturn(null);

        assertThrows(AiPrivacyInitializationPendingException.class,
                () -> guard.capture(mapper, session));
        assertStale(() -> guard.requireCurrent(mapper, new AiChatPrivacyToken(7, 13, 4, null, 1, 1L)));
        assertStale(() -> guard.requireCurrent(mapper, null));
    }

    @Test
    void everySecondaryDispatchRechecksCapturedGenerationWithoutContentLocks() {
        session.setMissionTaintSeq(9);
        AiChatPrivacyToken captured = guard.capture(mapper, session);

        guard.execute(mapper, captured, () -> {
            AiChatPrivacyGuard.requireEgressCurrent();
            barrier.setGeneration(5);
            barrier.setBlocked(false);
            assertStale(AiChatPrivacyGuard::requireEgressCurrent);
            return null;
        });

        assertDoesNotThrow(AiChatPrivacyGuard::requireEgressCurrent);
        verify(mapper, never()).lockPrivacyBarrierForShare(7);
        verify(mapper, never()).getSessionByIdForUpdate(7, 11, 13);
    }

    @Test
    void failedNestedExecutionRestoresOuterCapturedToken() {
        AiChatPrivacyToken outer = guard.capture(mapper, session);
        guard.execute(mapper, outer, () -> {
            assertThrows(IllegalStateException.class, () -> guard.execute(mapper, outer, () -> {
                throw new IllegalStateException("test interruption");
            }));
            session.setMissionTaintSeq(9);
            assertStale(AiChatPrivacyGuard::requireEgressCurrent);
            return null;
        });
        assertDoesNotThrow(AiChatPrivacyGuard::requireEgressCurrent);
    }

    @Test
    void rejectsOldOrganizationGenerationWithoutMissionTaint() {
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        when(authority.current(1)).thenReturn(2L);
        assertStale(() -> guard.requireCurrent(mapper, captured));
        assertStale(() -> guard.capture(mapper, session));
        assertEquals(1L, captured.organizationPrivacyGeneration());
    }

    @Test
    void providerAttemptUsesFreshAuthorityInsteadOfAnInputSnapshot() {
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        guard.execute(mapper, captured, () -> {
            when(authority.freshCurrent(1)).thenReturn(2L);
            java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
            assertStale(() -> AiChatPrivacyGuard.invokeAtEgress(() -> {
                sent.set(true);
                return true;
            }));
            org.junit.jupiter.api.Assertions.assertFalse(sent.get());
            return null;
        });
    }

    @Test
    void deletedSessionRefusesOriginalTokensAndDeferredProviderCallbacks() {
        AiChatPrivacyToken captured = guard.capture(mapper, session);
        when(mapper.getPrivacySession(7, 13)).thenReturn(null);

        assertStale(() -> guard.requireCurrent(mapper, captured));
        assertStale(() -> guard.retain(mapper, captured));
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        assertStale(() -> guard.execute(mapper, captured, () -> invoked.compareAndSet(false, true)));
        org.junit.jupiter.api.Assertions.assertFalse(invoked.get());
    }

    @Test
    void metadataReadRetainsTheOriginalOrganizationGeneration() {
        guard.retainOrganization(7, 1L);
        verify(authority).retain(1, 1L);
    }

    @Test
    void historicalZeroNeverAcquiresRuntimeAuthority() {
        session.setOrganizationPrivacyGeneration(0L);
        assertStale(() -> guard.capture(mapper, session));
        assertStale(() -> guard.retainOrganization(7, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new AiChatPrivacyToken(7, 13, 4, null, 1, 0L));
    }

    private static void assertStale(org.junit.jupiter.api.function.Executable action) {
        AiAssistantLoopException failure = assertThrows(AiAssistantLoopException.class, action);
        assertEquals("restrictions_changed", failure.detailReason());
    }
}
