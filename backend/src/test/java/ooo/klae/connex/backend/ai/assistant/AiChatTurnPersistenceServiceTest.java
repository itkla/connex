package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiChatTurnPersistenceServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of());

    private AiChatMapper chatMapper;
    private AttachmentMapper attachmentMapper;
    private WorkspaceService workspaceService;
    private AiChatTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        workspaceService = mock(WorkspaceService.class);
        AiProperties aiProperties = mock(AiProperties.class);
        service = new AiChatTurnPersistenceService(
                chatMapper,
                attachmentMapper,
                workspaceService,
                aiProperties,
                mock(AiRestrictionEpoch.class),
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
        User actor = new User();
        actor.setId(TURN.userId());
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(actor));
        when(aiProperties.getGenerationMaxLifetime()).thenReturn(Duration.ofMinutes(5));
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
                TURN.restrictionEpoch(), TURN.includePrivateNotes(), List.of(), List.of());
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

    @Test
    void queuedTurnSnapshotsSessionAttachmentsAndEnforcesCombinedContextCap() {
        Attachment first = attachment(31);
        Attachment second = attachment(37);
        when(attachmentMapper.getAssistantSessionAttachments(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of(first, second));
        AiChatTurnCreateRequest accepted = new AiChatTurnCreateRequest(
                "Summarize", List.of(new AiChatPageContextDto("person", 41)));

        AiChatQueuedTurn queued = service.queue(
                TURN.sessionId(), accepted, TURN.restrictionEpoch());

        assertEquals(List.of(31, 37), queued.attachmentIds());
        assertEquals(accepted.pageContext(), queued.pageContext());

        List<AiChatPageContextDto> tenRecords = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(id -> new AiChatPageContextDto("person", id))
                .toList();
        assertThrows(BadRequestException.class, () -> service.queue(
                TURN.sessionId(),
                new AiChatTurnCreateRequest("Summarize", tenRecords),
                TURN.restrictionEpoch()));
    }

    private static Attachment attachment(int id) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        return attachment;
    }
}
