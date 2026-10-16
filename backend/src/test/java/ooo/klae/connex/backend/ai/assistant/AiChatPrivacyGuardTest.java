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
import ooo.klae.connex.backend.mappers.AiChatMapper;

class AiChatPrivacyGuardTest {
    private final AiChatMapper mapper = mock(AiChatMapper.class);
    private final AiChatSession session = new AiChatSession();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();

    @BeforeEach
    void setUp() {
        session.setWorkspaceId(7);
        session.setId(13);
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(mapper.getPrivacySession(7, 13)).thenReturn(session);
        when(mapper.getPrivacyBarrier(7)).thenReturn(barrier);
        when(mapper.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
    }

    @Test
    void preTaintDerivedJobCannotPublishAfterTaint() {
        AiChatPrivacyToken captured = AiChatPrivacyGuard.capture(mapper, session);
        session.setMissionTaintSeq(9);
        session.setMissionTaintGeneration(4L);

        assertStale(() -> AiChatPrivacyGuard.requireCurrent(mapper, captured));
        assertStale(() -> AiChatPrivacyGuard.retain(mapper, captured));
    }

    @Test
    void staleGenerationRemainsRejectedAfterPurgeCompletesAndUnblocks() {
        session.setMissionTaintSeq(9);
        AiChatPrivacyToken captured = AiChatPrivacyGuard.capture(mapper, session);
        barrier.setGeneration(5);
        barrier.setBlocked(false);

        assertStale(() -> AiChatPrivacyGuard.requireCurrent(mapper, captured));
        assertStale(() -> AiChatPrivacyGuard.retain(mapper, captured));
        assertEquals(4, captured.privacyGeneration());
    }

    @Test
    void blockedTaintedSessionRefusesBothAdmissionAndExistingExecution() {
        session.setMissionTaintSeq(9);
        barrier.setBlocked(true);

        assertStale(() -> AiChatPrivacyGuard.capture(mapper, session));
        assertStale(() -> AiChatPrivacyGuard.requireCurrent(mapper, new AiChatPrivacyToken(7, 13, 4, 9)));
    }

    @Test
    void unrelatedUntaintedSessionRemainsUsableDuringCleanup() {
        AiChatPrivacyToken captured = AiChatPrivacyGuard.capture(mapper, session);
        barrier.setGeneration(5);
        barrier.setBlocked(true);

        assertDoesNotThrow(() -> AiChatPrivacyGuard.requireCurrent(mapper, captured));
        assertDoesNotThrow(() -> AiChatPrivacyGuard.retain(mapper, captured));
    }

    @Test
    void missingBarrierAndLegacyExecutionTokenFailClosed() {
        when(mapper.getPrivacyBarrier(7)).thenReturn(null);

        assertStale(() -> AiChatPrivacyGuard.capture(mapper, session));
        assertStale(() -> AiChatPrivacyGuard.requireCurrent(mapper, new AiChatPrivacyToken(7, 13, 4, null)));
        assertStale(() -> AiChatPrivacyGuard.requireCurrent(mapper, null));
    }

    @Test
    void everySecondaryDispatchRechecksCapturedGenerationWithoutContentLocks() {
        session.setMissionTaintSeq(9);
        AiChatPrivacyToken captured = AiChatPrivacyGuard.capture(mapper, session);

        AiChatPrivacyGuard.execute(mapper, captured, () -> {
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
        AiChatPrivacyToken outer = AiChatPrivacyGuard.capture(mapper, session);
        AiChatPrivacyGuard.execute(mapper, outer, () -> {
            assertThrows(IllegalStateException.class, () -> AiChatPrivacyGuard.execute(mapper, outer, () -> {
                throw new IllegalStateException("test interruption");
            }));
            session.setMissionTaintSeq(9);
            assertStale(AiChatPrivacyGuard::requireEgressCurrent);
            return null;
        });
        assertDoesNotThrow(AiChatPrivacyGuard::requireEgressCurrent);
    }

    private static void assertStale(org.junit.jupiter.api.function.Executable action) {
        AiAssistantLoopException failure = assertThrows(AiAssistantLoopException.class, action);
        assertEquals("restrictions_changed", failure.detailReason());
    }
}
