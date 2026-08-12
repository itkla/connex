package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiChatTurnPersistenceServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, false, List.of());

    private AiChatMapper chatMapper;
    private WorkspaceService workspaceService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiChatTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        workspaceService = mock(WorkspaceService.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        service = new AiChatTurnPersistenceService(
                chatMapper,
                workspaceService,
                mock(AiProperties.class),
                restrictionEpoch,
                Clock.systemUTC(),
                JsonMapper.builder().build());
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setVisibility("private");
        session.setStatus("active");
        AiChatTurn storedTurn = new AiChatTurn();
        storedTurn.setId(TURN.turnId());
        storedTurn.setRequestedByUserId(TURN.userId());
        storedTurn.setStatus("running");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(storedTurn);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(workspaceService.getCurrentUserId()).thenReturn(TURN.userId());
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(true);
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
    void firstResolvedAssistantTitleRequiresOwnershipAndAutoTitleProvenance() {
        AiChatQueuedTurn retryTurn = new AiChatQueuedTurn(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId(), TURN.turnId(),
                TURN.userMessageId(), 3, TURN.restrictionEpoch(), false, List.of());
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setTitleUserSet(false);
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.updateGeneratedTitle(
                TURN.workspaceId(), TURN.sessionId(), "Pipeline review")).thenReturn(1);

        assertTrue(service.applyGeneratedTitle(retryTurn, "Pipeline review"));

        session.setTitleUserSet(true);
        assertFalse(service.applyGeneratedTitle(retryTurn, "Replacement"));
        verify(chatMapper, times(1)).updateGeneratedTitle(
                TURN.workspaceId(), TURN.sessionId(), "Pipeline review");
    }

    @Test
    void restrictionEpochChangePreventsGeneratedTitlePersistence() {
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(false);

        assertFalse(service.applyGeneratedTitle(TURN, "Stale model title"));

        verify(chatMapper, never()).updateGeneratedTitle(
                TURN.workspaceId(), TURN.sessionId(), "Stale model title");
    }

    @Test
    void durableTurnStepKeyReplaysOnlyTheSameStepAndSeparatesIdenticalTurns() {
        AiAssistantPreparedWrite write = new AiAssistantPreparedWrite(
                "create_note",
                AiAssistantToolCatalog.ToolTier.AUTO,
                "person",
                31,
                "{\"tool\":\"create_note\",\"target\":{\"kind\":\"person\",\"id\":31}}");
        AiChatToolCall existing = new AiChatToolCall();
        existing.setId(47);
        existing.setSessionId(TURN.sessionId());
        existing.setRequestedByUserId(TURN.userId());
        existing.setToolName(write.toolName());
        existing.setArgumentsJson(write.argumentsJson());
        existing.setStatus("executed");
        existing.setResultJson("{\"outcome\":{\"status\":\"executed\"}}");
        when(chatMapper.getToolCallByIdempotencyKey(
                TURN.workspaceId(), "turn-17-step-1")).thenReturn(existing);

        AiAssistantToolProposal replay = service.proposeWriteTool(TURN, 1, write);

        assertEquals(47, replay.id());
        assertFalse(replay.created());
        verify(chatMapper, never()).insertToolCall(org.mockito.ArgumentMatchers.any());

        AiAssistantPreparedWrite changed = new AiAssistantPreparedWrite(
                write.toolName(), write.tier(),
                write.targetKind(), write.targetId(), "{\"changed\":true}");
        assertThrows(ConflictException.class, () -> service.proposeWriteTool(TURN, 1, changed));

        AiChatQueuedTurn secondTurn = new AiChatQueuedTurn(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId(), 18, 20, 2,
                TURN.restrictionEpoch(), TURN.includePrivateNotes(), List.of());
        AiChatTurn secondStoredTurn = new AiChatTurn();
        secondStoredTurn.setId(secondTurn.turnId());
        secondStoredTurn.setRequestedByUserId(secondTurn.userId());
        secondStoredTurn.setStatus("running");
        when(chatMapper.getTurnByIdForUpdate(
                secondTurn.workspaceId(), secondTurn.sessionId(), secondTurn.turnId()))
                .thenReturn(secondStoredTurn);

        AiAssistantToolProposal second = service.proposeWriteTool(secondTurn, 1, write);

        assertTrue(second.created());
        verify(chatMapper).insertToolCall(argThat(toolCall ->
                "turn-18-step-1".equals(toolCall.getIdempotencyKey())));
    }
}
