package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.WorkspaceService;

class AiChatTurnPersistenceServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, false, List.of());

    private AiChatMapper chatMapper;
    private AiChatTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        service = new AiChatTurnPersistenceService(
                chatMapper,
                mock(WorkspaceService.class),
                mock(AiProperties.class),
                mock(AiRestrictionEpoch.class),
                Clock.systemUTC(),
                mock(AiChatRealtimeDispatcher.class));
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        AiChatTurn storedTurn = new AiChatTurn();
        storedTurn.setId(TURN.turnId());
        storedTurn.setRequestedByUserId(TURN.userId());
        storedTurn.setStatus("running");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(storedTurn);
    }

    @Test
    void generationOwnedTerminalAndToolWritesLockSessionRootBeforeChildRows() {
        when(chatMapper.updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "provider_error", null, null)).thenReturn(1);

        assertTrue(service.markTerminal(TURN, "failed", "provider_error"));

        InOrder terminalOrder = inOrder(chatMapper);
        terminalOrder.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        terminalOrder.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        terminalOrder.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "provider_error", null, null);

        clearInvocations(chatMapper);
        when(chatMapper.updateToolCall(
                TURN.workspaceId(), TURN.userMessageId(), 29,
                "failed", "{\"reason\":\"internal_error\"}", TURN.userId())).thenReturn(1);

        assertTrue(service.failTool(TURN, 29, "{\"reason\":\"internal_error\"}"));

        InOrder toolOrder = inOrder(chatMapper);
        toolOrder.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        toolOrder.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        toolOrder.verify(chatMapper).updateToolCall(
                TURN.workspaceId(), TURN.userMessageId(), 29,
                "failed", "{\"reason\":\"internal_error\"}", TURN.userId());
    }

    @Test
    void sharedSessionWithoutParticipantsNeverEnablesPrivateNotes() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AiChatRealtimeDispatcher dispatcher = mock(AiChatRealtimeDispatcher.class);
        AiChatTurnPersistenceService queueService = new AiChatTurnPersistenceService(
                chatMapper,
                workspaceService,
                new AiProperties(),
                mock(AiRestrictionEpoch.class),
                Clock.systemUTC(),
                dispatcher);
        User owner = new User();
        owner.setId(TURN.userId());
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setWorkspaceId(TURN.workspaceId());
        session.setCreatedByUserId(TURN.userId());
        session.setVisibility("shared");
        session.setStatus("active");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(workspaceService.getCurrentUserId()).thenReturn(TURN.userId());
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.listActiveTurnsBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.nextMessageSequence(TURN.workspaceId(), TURN.sessionId())).thenReturn(1);

        AiChatQueuedTurn queued = queueService.queue(
                TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of()),
                TURN.restrictionEpoch());

        assertFalse(queued.includePrivateNotes());
    }
}
