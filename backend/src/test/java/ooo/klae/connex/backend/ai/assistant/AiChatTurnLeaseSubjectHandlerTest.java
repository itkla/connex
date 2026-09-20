package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.mappers.AiChatMapper;

/** Proves the lease's cross-instance stop signal for a chat turn reads status without locking. */
class AiChatTurnLeaseSubjectHandlerTest {
    private static final int WORKSPACE_ID = 7;
    private static final int TURN_ID = 17;

    private AiChatMapper chatMapper;
    private AiChatTurnLeaseSubjectHandler handler;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        handler = new AiChatTurnLeaseSubjectHandler(chatMapper);
    }

    @Test
    void theHandlerOwnsTheChatTurnSubjectKind() {
        assertEquals(AiRunLeaseSubject.CHAT_TURN, handler.subject());
    }

    @Test
    void onlyARunningTurnLetsItsOwnerKeepWorking() {
        when(chatMapper.getTurnStatus(WORKSPACE_ID, TURN_ID)).thenReturn("running");
        assertTrue(handler.isSubjectRunning(WORKSPACE_ID, TURN_ID));

        for (String stopped : new String[] {
                "queued", "cancelled", "failed", "resolved", "timed_out"}) {
            when(chatMapper.getTurnStatus(WORKSPACE_ID, TURN_ID)).thenReturn(stopped);
            assertFalse(handler.isSubjectRunning(WORKSPACE_ID, TURN_ID), stopped);
        }
    }

    @Test
    void aTurnThisWorkspaceDoesNotHoldStopsItsOwnerRatherThanFailing() {
        when(chatMapper.getTurnStatus(WORKSPACE_ID, TURN_ID)).thenReturn(null);

        assertFalse(handler.isSubjectRunning(WORKSPACE_ID, TURN_ID));
    }

    @Test
    void thelivenessReadNeverTakesARowLock() {
        when(chatMapper.getTurnStatus(WORKSPACE_ID, TURN_ID)).thenReturn("running");

        handler.isSubjectRunning(WORKSPACE_ID, TURN_ID);

        verify(chatMapper).getTurnStatus(WORKSPACE_ID, TURN_ID);
        verify(chatMapper, never()).getTurnByIdForUpdate(anyInt(), anyInt(), anyInt());
        verify(chatMapper, never()).getSessionByIdForUpdate(anyInt(), anyInt(), anyInt());
    }

    @Test
    void orphanSettlementIsRefusedUntilASweeperCanDispatchIt() {
        AiRunLease takeover = new AiRunLease(
                new AiRunLeaseKey(WORKSPACE_ID, AiRunLeaseSubject.CHAT_TURN, TURN_ID),
                "11111111-2222-3333-4444-555555555555",
                2L);

        assertThrows(
                UnsupportedOperationException.class,
                () -> handler.settleOrphan(WORKSPACE_ID, TURN_ID, takeover));
    }
}
