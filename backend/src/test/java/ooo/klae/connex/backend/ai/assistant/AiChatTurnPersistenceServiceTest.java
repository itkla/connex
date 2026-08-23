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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiChatTurnPersistenceServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of());
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    private AiChatMapper chatMapper;
    private AttachmentMapper attachmentMapper;
    private WorkspaceService workspaceService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiWorkspaceGovernanceService governanceService;
    private AiAssistantIdentifierResolver identifierResolver;
    private AiAssistantToolExecutor toolExecutor;
    private AiChatTurn storedTurn;
    private AiChatRealtimeDispatcher realtimeDispatcher;
    private AiChatTurnPersistenceService service;

    @BeforeEach
    void setUp() {
        chatMapper = mock(AiChatMapper.class);
        attachmentMapper = mock(AttachmentMapper.class);
        workspaceService = mock(WorkspaceService.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        governanceService = mock(AiWorkspaceGovernanceService.class);
        identifierResolver = mock(AiAssistantIdentifierResolver.class);
        toolExecutor = mock(AiAssistantToolExecutor.class);
        realtimeDispatcher = mock(AiChatRealtimeDispatcher.class);
        service = new AiChatTurnPersistenceService(
                chatMapper,
                attachmentMapper,
                workspaceService,
                restrictionEpoch,
                governanceService,
                identifierResolver,
                toolExecutor,
                Clock.fixed(NOW, ZoneOffset.UTC),
                realtimeDispatcher,
                JsonMapper.builder().build());
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setVisibility("private");
        session.setStatus("active");
        storedTurn = new AiChatTurn();
        storedTurn.setId(TURN.turnId());
        storedTurn.setWorkspaceId(TURN.workspaceId());
        storedTurn.setSessionId(TURN.sessionId());
        storedTurn.setRequestedByUserId(TURN.userId());
        storedTurn.setStatus("running");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(storedTurn);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(TURN.workspaceId());
        when(workspaceService.getCurrentUserId()).thenReturn(TURN.userId());
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(true);
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        User actor = new User();
        actor.setId(TURN.userId());
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(actor));
        when(identifierResolver.resolve(any()))
                .thenReturn(AiAssistantIdentifierResolver.Resolution.empty());
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
    void durableTerminalProjectionUsesTheStoredWinnerAndUtf16Offset() {
        storedTurn.setStatus("resolved");
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(31);

        AiChatDurableTerminal terminal = service.terminalState(TURN);

        assertEquals(new AiChatDurableTerminal("resolved", null, 31), terminal);
    }

    @Test
    void appendPartialBatchPersistsAndPublishesTheSameUtf16BatchOnlyToRequester() {
        AiChatQueuedTurn streamed = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                AiPrivacyMode.UNMASKED, true);
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(0);
        when(chatMapper.appendTurnPartialContent(7, 13, 17, 0, "A😀", 3)).thenReturn(1);

        assertEquals(3, service.appendPartialBatch(streamed, 0, "A😀"));

        verify(realtimeDispatcher).userAfterCommit(
                org.mockito.ArgumentMatchers.eq(11),
                argThat(frame -> frame.seq() == 0
                        && "delta".equals(frame.kind())
                        && "A😀".equals(frame.text())));
    }

    @Test
    void revokedParticipantCannotPersistOrReceiveAnotherStreamBatch() {
        AiChatQueuedTurn streamed = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                AiPrivacyMode.UNMASKED, true);
        AiChatSession shared = new AiChatSession();
        shared.setId(TURN.sessionId());
        shared.setCreatedByUserId(99);
        shared.setVisibility("shared");
        shared.setStatus("active");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(shared);
        when(chatMapper.isParticipant(
                TURN.workspaceId(), TURN.sessionId(), TURN.userId())).thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.appendPartialBatch(streamed, 0, "Revoked"));

        verify(chatMapper, never()).appendTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 0, "Revoked", 7);
        verify(realtimeDispatcher, never()).userAfterCommit(
                org.mockito.ArgumentMatchers.eq(TURN.userId()), any());
    }

    @Test
    void changedRestrictionEpochRejectsAStreamBatchBeforePersistence() {
        AiChatQueuedTurn streamed = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                AiPrivacyMode.UNMASKED, true);
        storedTurn.setStreamed(true);
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(false);

        AiAssistantLoopException exception = assertThrows(
                AiAssistantLoopException.class,
                () -> service.appendPartialBatch(streamed, 0, "Restricted"));

        assertEquals("restrictions_changed", exception.terminalReason());
        verify(chatMapper, never()).appendTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 0, "Restricted", 10);
    }

    @Test
    void failedTerminalizationRetainsAScreenableDurablePartialAnswer() {
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(7);
        storedTurn.setPartialContent("Atlas renewal is slipping");
        when(chatMapper.updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "restrictions_changed", null, null)).thenReturn(1);

        assertTrue(service.markTerminal(TURN, "failed", "restrictions_changed"));

        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        order.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "restrictions_changed", null, null);
        verify(chatMapper, never()).resetTurnPartialContent(
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void failedTerminalizationPurgesAPartialAnswerTheSpecialCareScreenExcludes() {
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(7);
        storedTurn.setPartialContent("The contact disclosed a cancer diagnosis during the call.");
        when(chatMapper.resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7)).thenReturn(1);
        when(chatMapper.updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "restrictions_changed", null, null)).thenReturn(1);

        assertTrue(service.markTerminal(TURN, "failed", "restrictions_changed"));

        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7);
        order.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "restrictions_changed", null, null);
    }

    @Test
    void resetPartialContentClearsTheExactOffsetAndPublishesStateInvalidation() {
        AiChatQueuedTurn streamed = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                AiPrivacyMode.UNMASKED, true);
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(3);
        when(chatMapper.resetTurnPartialContent(7, 13, 17, 3)).thenReturn(1);

        service.resetPartialContent(streamed, 3);

        verify(realtimeDispatcher).sessionAfterCommit(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(13),
                argThat(frame -> frame.seq() == 0
                        && "reset".equals(frame.kind())
                        && "running".equals(frame.status())
                        && frame.text() == null));
    }

    @Test
    void ownerCanCancelActiveTurnAndTerminalTurnConflicts() {
        when(chatMapper.cancelTurn(TURN.workspaceId(), TURN.sessionId(), TURN.turnId()))
                .thenReturn(1);

        service.cancel(TURN.sessionId(), TURN.turnId());

        verify(chatMapper).cancelTurn(TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        verify(realtimeDispatcher).sessionAfterCommit(
                org.mockito.ArgumentMatchers.eq(TURN.workspaceId()),
                org.mockito.ArgumentMatchers.eq(TURN.sessionId()),
                argThat(frame -> "cancelled".equals(frame.status())));

        storedTurn.setStatus("resolved");
        assertThrows(ConflictException.class,
                () -> service.cancel(TURN.sessionId(), TURN.turnId()));
    }

    @Test
    void joinedNonRequesterCannotCancelAnotherParticipantsTurn() {
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(99);
        session.setVisibility("shared");
        session.setStatus("active");
        storedTurn.setRequestedByUserId(88);
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.isParticipant(
                TURN.workspaceId(), TURN.sessionId(), TURN.userId())).thenReturn(true);

        assertThrows(ForbiddenException.class,
                () -> service.cancel(TURN.sessionId(), TURN.turnId()));

        verify(chatMapper, never()).cancelTurn(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
    }

    @Test
    void nonParticipantCannotCancelAndJoinedRequesterCan() {
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(99);
        session.setVisibility("shared");
        session.setStatus("active");
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);

        assertThrows(ForbiddenException.class,
                () -> service.cancel(TURN.sessionId(), TURN.turnId()));

        when(chatMapper.isParticipant(
                TURN.workspaceId(), TURN.sessionId(), TURN.userId())).thenReturn(true);
        when(chatMapper.cancelTurn(TURN.workspaceId(), TURN.sessionId(), TURN.turnId()))
                .thenReturn(1);

        service.cancel(TURN.sessionId(), TURN.turnId());

        verify(chatMapper).cancelTurn(TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
    }

    @Test
    void sharedSessionWithoutParticipantsNeverEnablesPrivateNotes() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AiChatRealtimeDispatcher dispatcher = mock(AiChatRealtimeDispatcher.class);
        AiAssistantIdentifierResolver identifierResolver =
                mock(AiAssistantIdentifierResolver.class);
        AiAssistantToolExecutor toolExecutor = mock(AiAssistantToolExecutor.class);
        AiWorkspaceGovernanceService governanceService =
                mock(AiWorkspaceGovernanceService.class);
        AiChatTurnPersistenceService queueService = new AiChatTurnPersistenceService(
                chatMapper,
                attachmentMapper,
                workspaceService,
                mock(AiRestrictionEpoch.class),
                governanceService,
                identifierResolver,
                toolExecutor,
                Clock.systemUTC(),
                dispatcher,
                JsonMapper.builder().build());
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
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(true);
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.listActiveTurnsBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.nextMessageSequence(TURN.workspaceId(), TURN.sessionId())).thenReturn(1);
        when(identifierResolver.resolve("Question"))
                .thenReturn(new AiAssistantIdentifierResolver.Resolution(
                        List.of(new AiChatPageContextDto("person", 31)),
                        List.of(new AiAssistantIdentifierResolver.Identifier(
                                ooo.klae.connex.backend.ai.masking.EntityKind.PERSON,
                                "Ada Lovelace"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            AiChatResourceRegistry registry = invocation.getArgument(1);
            registry.register("deal", 47);
            return new AiAssistantToolResult(Map.of(), List.of());
        }).when(toolExecutor).pageContext(any(), any());
        when(attachmentMapper.getAssistantSessionAttachments(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());

        AiChatQueuedTurn queued = queueService.queue(
                TURN.sessionId(), new AiChatTurnCreateRequest(
                        "Question", List.of(new AiChatPageContextDto("deal", 47))),
                TURN.restrictionEpoch());

        assertFalse(queued.includePrivateNotes());
        verify(identifierResolver).resolve("Question");
        verify(chatMapper).insertMessage(argThat(message ->
                message.getStructuredJson() != null
                        && message.getStructuredJson().contains("\"kind\":\"person\"")
                        && message.getStructuredJson().contains("\"id\":31")
                        && message.getStructuredJson().contains("\"value\":\"Ada Lovelace\"")
                        && message.getStructuredJson().contains("\"kind\":\"deal\"")
                        && message.getStructuredJson().contains("\"id\":47")));
    }

    @Test
    void readPastDeadlineAndGraceTerminalizesTheTurnAndFreesTheSession() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                NOW.minus(AiAssistantTurnBudget.DURABLE_LIFETIME), ZoneOffset.UTC);
        AiChatTurn expired = new AiChatTurn();
        expired.setId(TURN.turnId());
        expired.setWorkspaceId(TURN.workspaceId());
        expired.setSessionId(TURN.sessionId());
        expired.setRequestedByUserId(TURN.userId());
        expired.setStatus("timed_out");
        expired.setTerminalReason("generation_timeout");
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId()))
                .thenReturn(storedTurn, expired);
        when(chatMapper.updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "timed_out", "generation_timeout", "running", cutoff)).thenReturn(1);
        when(chatMapper.listActiveTurnsBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.countActiveTurns(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(0);
        when(attachmentMapper.getAssistantSessionAttachments(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.nextMessageSequence(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(2);

        AiChatTurn read = service.readTurn(TURN.sessionId(), TURN.turnId());
        AiChatQueuedTurn next = service.queue(
                TURN.sessionId(), new AiChatTurnCreateRequest("Next question", List.of()),
                TURN.restrictionEpoch());

        assertEquals("timed_out", read.getStatus());
        assertEquals("generation_timeout", read.getTerminalReason());
        assertEquals(TURN.sessionId(), next.sessionId());
        verify(chatMapper).countActiveTurns(TURN.workspaceId(), TURN.sessionId());
    }

    @Test
    void firstResolvedAssistantTitleRequiresOwnershipAndAutoTitleProvenance() {
        AiChatQueuedTurn retryTurn = new AiChatQueuedTurn(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId(), TURN.turnId(),
                TURN.userMessageId(), 3, TURN.restrictionEpoch(), false, List.of(), List.of());
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
        AiChatSession session = new AiChatSession();
        session.setId(TURN.sessionId());
        session.setCreatedByUserId(TURN.userId());
        session.setTitleUserSet(false);
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(false);

        assertFalse(service.applyGeneratedTitle(TURN, "Stale model title"));

        InOrder order = inOrder(chatMapper, restrictionEpoch);
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(restrictionEpoch).retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch());
        verify(chatMapper, never()).updateGeneratedTitle(
                TURN.workspaceId(), TURN.sessionId(), "Stale model title");
    }

    @Test
    void assistantPersistenceAcquiresDatabaseRowsBeforeTheRestrictionFence() {
        when(restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch())).thenReturn(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(
                    AiAssistantLoopException.class,
                    () -> service.upsertHistorySummary(
                            TURN, null, 0, "Summary",
                            "{\"kind\":\"history_summary\"}", 3, 2));
            InOrder summaryOrder = inOrder(chatMapper, restrictionEpoch);
            summaryOrder.verify(chatMapper).getSessionByIdForUpdate(
                    TURN.workspaceId(), TURN.userId(), TURN.sessionId());
            summaryOrder.verify(chatMapper).getTurnByIdForUpdate(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
            summaryOrder.verify(restrictionEpoch)
                    .retainReadFenceUntilTransactionCompletionIfCurrent(
                            TURN.workspaceId(), TURN.restrictionEpoch());

            clearInvocations(chatMapper, restrictionEpoch);
            assertThrows(
                    AiAssistantLoopException.class,
                    () -> service.finishTool(TURN, 29, "executed", "{}"));
            InOrder toolOrder = inOrder(chatMapper, restrictionEpoch);
            toolOrder.verify(chatMapper).getSessionByIdForUpdate(
                    TURN.workspaceId(), TURN.userId(), TURN.sessionId());
            toolOrder.verify(chatMapper).getTurnByIdForUpdate(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
            toolOrder.verify(restrictionEpoch)
                    .retainReadFenceUntilTransactionCompletionIfCurrent(
                            TURN.workspaceId(), TURN.restrictionEpoch());

            clearInvocations(chatMapper, restrictionEpoch);
            assertThrows(
                    AiAssistantLoopException.class,
                    () -> service.resolve(TURN, "Answer", null, 5, 3));
            InOrder resolveOrder = inOrder(chatMapper, restrictionEpoch);
            resolveOrder.verify(chatMapper).getSessionByIdForUpdate(
                    TURN.workspaceId(), TURN.userId(), TURN.sessionId());
            resolveOrder.verify(chatMapper).getTurnByIdForUpdate(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
            resolveOrder.verify(restrictionEpoch)
                    .retainReadFenceUntilTransactionCompletionIfCurrent(
                            TURN.workspaceId(), TURN.restrictionEpoch());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void governanceDisableBeforeFinalAnswerBlocksPersistence() {
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(
                    ForbiddenException.class,
                    () -> service.resolve(TURN, "Answer", null, 5, 3));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(chatMapper, never()).insertMessage(any());
    }

    @Test
    void lifecycleTeardownBeforeAccessCheckBlocksQueueAndLazyRead() {
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(false);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.queue(
                        TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of()),
                        TURN.restrictionEpoch()));
        assertThrows(
                ResourceNotFoundException.class,
                () -> service.readTurn(TURN.sessionId(), TURN.turnId()));

        verify(chatMapper, never()).insertMessage(any());
        verify(chatMapper, never()).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
    }

    @Test
    void governanceDisableBeforeAccessCheckBlocksQueueAndLazyRead() {
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(false);

        assertThrows(
                ForbiddenException.class,
                () -> service.queue(
                        TURN.sessionId(), new AiChatTurnCreateRequest("Question", List.of()),
                        TURN.restrictionEpoch()));
        assertThrows(
                ForbiddenException.class,
                () -> service.readTurn(TURN.sessionId(), TURN.turnId()));

        verify(chatMapper, never()).insertMessage(any());
        verify(chatMapper, never()).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
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
    void nativeThoughtSignatureSurvivesReadAndWriteProposalPersistence() {
        String thoughtSignature = "opaque /+==\nline two";

        service.proposeTool(
                TURN, 1, "search_records", "{\"query\":\"pipeline\"}",
                thoughtSignature);
        AiAssistantPreparedWrite write = new AiAssistantPreparedWrite(
                "create_note",
                AiAssistantToolCatalog.ToolTier.AUTO,
                "person",
                31,
                "{\"tool\":\"create_note\",\"target\":{\"kind\":\"person\",\"id\":31}}");
        service.proposeWriteTool(TURN, 2, write, thoughtSignature);

        ArgumentCaptor<AiChatToolCall> persisted =
                ArgumentCaptor.forClass(AiChatToolCall.class);
        verify(chatMapper, times(2)).insertToolCall(persisted.capture());
        assertEquals(thoughtSignature,
                persisted.getAllValues().getFirst().getThoughtSignature());
        assertEquals(thoughtSignature,
                persisted.getAllValues().getLast().getThoughtSignature());
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
