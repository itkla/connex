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
import static org.mockito.ArgumentMatchers.eq;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
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
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.json.JsonMapper;

class AiChatTurnPersistenceServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(), AiPrivacyMode.MASKED, false, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 1, null));
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final AiRunLeaseKey LEASE_KEY = new AiRunLeaseKey(
            TURN.workspaceId(), AiRunLeaseSubject.CHAT_TURN, TURN.turnId());

    private AiAssistantSessionReadAudit sessionReadAudit;
    private AiChatSession auditedSession;
    private AiChatMapper chatMapper;
    private AttachmentMapper attachmentMapper;
    private WorkspaceService workspaceService;
    private AiRestrictionEpoch restrictionEpoch;
    private AiWorkspaceGovernanceService governanceService;
    private AiAssistantIdentifierResolver identifierResolver;
    private AiAssistantToolExecutor toolExecutor;
    private AiChatTurn storedTurn;
    private AiChatRealtimeDispatcher realtimeDispatcher;
    private AiRunLeaseService runLeaseService;
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
        sessionReadAudit = mock(AiAssistantSessionReadAudit.class);
        runLeaseService = mock(AiRunLeaseService.class);
        service = new AiChatTurnPersistenceService(
                chatMapper,
                attachmentMapper,
                workspaceService,
                restrictionEpoch,
                governanceService,
                identifierResolver,
                toolExecutor,
                sessionReadAudit,
                Clock.fixed(NOW, ZoneOffset.UTC),
                realtimeDispatcher,
                JsonMapper.builder().build(),
                runLeaseService);
        AiChatSession session = new AiChatSession();
        auditedSession = session;
        session.setId(TURN.sessionId());
        session.setWorkspaceId(TURN.workspaceId());
        session.setCreatedByUserId(TURN.userId());
        session.setVisibility("private");
        session.setStatus("active");
        storedTurn = new AiChatTurn();
        storedTurn.setId(TURN.turnId());
        storedTurn.setWorkspaceId(TURN.workspaceId());
        storedTurn.setSessionId(TURN.sessionId());
        storedTurn.setRequestedByUserId(TURN.userId());
        storedTurn.setStatus("running");
        storedTurn.setPrivacyGeneration(1L);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setId(29);
        toolCall.setMessageId(TURN.userMessageId());
        toolCall.setArtifactOrigin("reactive");
        toolCall.setPrivacyGeneration(1L);
        when(chatMapper.getToolCallBySessionForUpdate(TURN.workspaceId(), TURN.sessionId(), 29))
                .thenReturn(toolCall);
        AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(TURN.workspaceId());
        barrier.setGeneration(1);
        when(chatMapper.getPrivacyBarrier(TURN.workspaceId())).thenReturn(barrier);
        when(chatMapper.lockPrivacyBarrierForShare(TURN.workspaceId())).thenReturn(barrier);
        when(chatMapper.getPrivacySession(TURN.workspaceId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId())).thenReturn(session);
        when(chatMapper.getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(storedTurn);
        when(chatMapper.getTurnById(TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(storedTurn);
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
        toolOrder.verify(chatMapper).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), 29);
        toolOrder.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        toolOrder.verify(chatMapper).updateToolCall(
                TURN.workspaceId(), TURN.userMessageId(), 29,
                "failed", "{\"reason\":\"internal_error\"}", TURN.userId());
    }

    @Test
    void reactiveToolCallbacksRefuseMissingMissionAndWrongMessageSourcesBeforeTurnLock() {
        when(chatMapper.getToolCallBySessionForUpdate(TURN.workspaceId(), TURN.sessionId(), 29))
                .thenReturn(null);
        assertThrows(ConflictException.class, () -> service.finishTool(TURN, 29, "executed", "{}"));
        assertThrows(ConflictException.class, () -> service.failTool(TURN, 29, "{}"));
        AiChatToolCall call = new AiChatToolCall();
        call.setMessageId(TURN.userMessageId());
        call.setMissionRunId(41);
        when(chatMapper.getToolCallBySessionForUpdate(TURN.workspaceId(), TURN.sessionId(), 29))
                .thenReturn(call);
        assertThrows(ConflictException.class, () -> service.finishTool(TURN, 29, "executed", "{}"));
        assertThrows(ConflictException.class, () -> service.failTool(TURN, 29, "{}"));
        call.setMissionRunId(null);
        call.setArtifactOrigin("mission");
        assertThrows(ConflictException.class, () -> service.finishTool(TURN, 29, "executed", "{}"));
        assertThrows(ConflictException.class, () -> service.failTool(TURN, 29, "{}"));
        call.setArtifactOrigin("reactive");
        call.setMessageId(TURN.userMessageId() + 1);
        assertThrows(ConflictException.class, () -> service.finishTool(TURN, 29, "executed", "{}"));
        assertThrows(ConflictException.class, () -> service.failTool(TURN, 29, "{}"));

        verify(chatMapper, never()).getTurnByIdForUpdate(TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        verify(chatMapper, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
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
                AiPrivacyMode.UNMASKED, true, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 1, null));
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
                AiPrivacyMode.UNMASKED, true, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 1, null));
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
                AiPrivacyMode.UNMASKED, true, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 1, null));
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
                "failed", "provider_error", null, null)).thenReturn(1);

        assertTrue(service.markTerminal(TURN, "failed", "provider_error"));

        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        order.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "provider_error", null, null);
        verify(chatMapper, never()).resetTurnPartialContent(
                anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void cancelledAndTimedOutTerminalizationRetainTheDurablePartialAnswer() {
        when(chatMapper.updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        for (String[] terminal : new String[][] {
                {"cancelled", "cancelled"},
                {"timed_out", "generation_timeout"},
                {"failed", "no_progress"}}) {
            clearInvocations(chatMapper);
            storedTurn.setStreamed(true);
            storedTurn.setPartialContentUtf16Offset(7);
            storedTurn.setPartialContent("Atlas renewal is slipping");

            assertTrue(service.markTerminal(TURN, terminal[0], terminal[1]));

            verify(chatMapper).updateTurnTerminal(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                    terminal[0], terminal[1], null, null);
            verify(chatMapper, never()).resetTurnPartialContent(
                    anyInt(), anyInt(), anyInt(), anyInt());
        }
    }

    @Test
    void authorizationWithdrawnTerminalizationPurgesTheDurablePartialAnswer() {
        when(chatMapper.resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7)).thenReturn(1);
        when(chatMapper.updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        for (String reason : AiAssistantTerminalReasons.AUTHORIZATION_WITHDRAWN) {
            clearInvocations(chatMapper);
            storedTurn.setStreamed(true);
            storedTurn.setPartialContentUtf16Offset(7);
            storedTurn.setPartialContent("Atlas renewal is slipping");

            assertTrue(service.markTerminal(TURN, "failed", reason));

            InOrder order = inOrder(chatMapper);
            order.verify(chatMapper).resetTurnPartialContent(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7);
            order.verify(chatMapper).updateTurnTerminal(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                    "failed", reason, null, null);
        }
    }

    @Test
    void malformedOutputTerminalizationPurgesTheDurablePartialAnswer() {
        when(chatMapper.resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7)).thenReturn(1);
        when(chatMapper.updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(7);
        storedTurn.setPartialContent("Atlas renewal is slipping");

        assertTrue(service.markTerminal(TURN, "failed", "malformed_output"));

        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7);
        order.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "malformed_output", null, null);
    }

    @Test
    void authorizationWithdrawnTerminalizationFailsClosedWhenThePurgeMissesItsRow() {
        storedTurn.setStreamed(true);
        storedTurn.setPartialContentUtf16Offset(7);
        storedTurn.setPartialContent("Atlas renewal is slipping");
        when(chatMapper.resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7)).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.markTerminal(TURN, "failed", "access_revoked"));

        verify(chatMapper, never()).updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any());
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
                "failed", "provider_error", null, null)).thenReturn(1);

        assertTrue(service.markTerminal(TURN, "failed", "provider_error"));

        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).resetTurnPartialContent(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 7);
        order.verify(chatMapper).updateTurnTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "provider_error", null, null);
    }

    @Test
    void resetPartialContentClearsTheExactOffsetAndPublishesStateInvalidation() {
        AiChatQueuedTurn streamed = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                AiPrivacyMode.UNMASKED, true, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 1, null));
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
                restrictionEpoch,
                governanceService,
                identifierResolver,
                toolExecutor,
                mock(AiAssistantSessionReadAudit.class),
                Clock.systemUTC(),
                dispatcher,
                JsonMapper.builder().build(),
                mock(AiRunLeaseService.class));
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

    /**
     * Starting a turn runs the agent loop over another member's transcript, so it earns its own
     * record. The ordering is the fix, not an accident: appending the row before
     * {@code lockAndRequireMember} keeps this path and a membership mutation in one global lock
     * order. Reverting the placement reintroduces the deadlock cycle, so the order is asserted.
     */
    @Test
    void queueingATurnRecordsTheAgentLoopReadBeforeTakingAnyLock() {
        when(chatMapper.listActiveTurnsBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.countActiveTurns(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(0);
        when(attachmentMapper.getAssistantSessionAttachments(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(List.of());
        when(chatMapper.nextMessageSequence(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(2);

        service.queue(
                TURN.sessionId(), new AiChatTurnCreateRequest("A question", List.of()),
                TURN.restrictionEpoch());

        InOrder ordered = inOrder(sessionReadAudit, workspaceService);
        ordered.verify(sessionReadAudit).recordAccessible(
                TURN.workspaceId(), TURN.userId(), auditedSession);
        ordered.verify(workspaceService).lockAndRequireMember(
                TURN.workspaceId(), TURN.userId());
    }

    /** Following your own turn polls this route once per second, so it must record nothing. */
    @Test
    void readingATurnRecordsNothingOnThePollingPath() {
        service.readTurn(TURN.sessionId(), TURN.turnId());
        service.readTurn(TURN.sessionId(), TURN.turnId());

        verify(sessionReadAudit, never()).recordAccessible(
                anyInt(), anyInt(), any(AiChatSession.class));
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
        storedTurn.setStatus("resolved");
        AiChatQueuedTurn retryTurn = new AiChatQueuedTurn(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId(), TURN.turnId(),
                TURN.userMessageId(), 3, TURN.restrictionEpoch(), false, List.of(), List.of(), AiPrivacyMode.MASKED, false, AiChatQueryScope.none(), new AiChatPrivacyToken(TURN.workspaceId(), TURN.sessionId(), 1, null));
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
            toolOrder.verify(chatMapper).getToolCallBySessionForUpdate(
                    TURN.workspaceId(), TURN.sessionId(), 29);
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
                TURN.restrictionEpoch(), TURN.includePrivateNotes(), List.of(), List.of(), AiPrivacyMode.MASKED, false, AiChatQueryScope.none(), new AiChatPrivacyToken(TURN.workspaceId(), TURN.sessionId(), 1, null));
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

    /**
     * The loop reads what already holds a write step's key before it prepares that step: the
     * step's sole-call key only, after the caller's current access is revalidated, and without
     * taking any lock.
     */
    @Test
    void storedWriteArgumentsReadTheStepsSoleCallKeyWithoutLocking() {
        when(workspaceService.permissionsFor(TURN.workspaceId(), TURN.userId()))
                .thenReturn(Set.of(Permission.AI_USE));
        AiChatToolCall existing = new AiChatToolCall();
        existing.setArgumentsJson("{\"tool\":\"assign_owner\",\"principals\":[21]}");
        when(chatMapper.getToolCallByIdempotencyKey(TURN.workspaceId(), "turn-17-step-3"))
                .thenReturn(existing);

        assertEquals(
                Optional.of(existing.getArgumentsJson()), service.storedWriteArguments(TURN, 3));
        assertEquals(Optional.empty(), service.storedWriteArguments(TURN, 4));
        verify(chatMapper, never()).getSessionByIdForUpdate(anyInt(), anyInt(), anyInt());
        verify(chatMapper, never()).getTurnByIdForUpdate(anyInt(), anyInt(), anyInt());
        verify(workspaceService, never()).lockAndRequireMember(anyInt(), anyInt());

        when(workspaceService.permissionsFor(TURN.workspaceId(), TURN.userId()))
                .thenReturn(Set.of());
        assertThrows(
                ResourceNotFoundException.class, () -> service.storedWriteArguments(TURN, 3));
    }

    /**
     * The key a step's only call writes has not changed, and a batched one suffixes it.
     *
     * <p>Ordinal 0 means "the sole call of its step" and has to render the exact legacy key: every
     * write, every {@code find_tools}, every unbatched read and every server-side plan step writes
     * it, the write-proposal replay looks it up verbatim, and the progress projection scans rows by
     * the {@code turn-N-step-} prefix. Only a call that shared its step renders {@code -call-k},
     * which fits the existing column and its uniqueness constraint without any schema change.
     */
    @Test
    void theSoleCallOfAStepKeepsItsLegacyKeyWhileBatchedCallsSuffixTheirOrdinal() {
        service.proposeTool(TURN, 4, 0, "search_records", "{}");
        service.proposeTool(TURN, 5, 1, "search_records", "{}");
        service.proposeTool(TURN, 5, 2, "list_tasks", "{}");

        ArgumentCaptor<AiChatToolCall> persisted =
                ArgumentCaptor.forClass(AiChatToolCall.class);
        verify(chatMapper, times(3)).insertToolCall(persisted.capture());
        assertEquals(
                List.of(
                        "turn-" + TURN.turnId() + "-step-4",
                        "turn-" + TURN.turnId() + "-step-5-call-1",
                        "turn-" + TURN.turnId() + "-step-5-call-2"),
                persisted.getAllValues().stream()
                        .map(AiChatToolCall::getIdempotencyKey)
                        .toList());
        assertTrue(persisted.getAllValues().getFirst().getIdempotencyKey().length() <= 64);
        assertTrue(persisted.getAllValues().getLast().getIdempotencyKey().length() <= 64);
    }

    /**
     * An ordinal outside the per-step call ceiling writes no row at all.
     *
     * <p>Bounded on both sides for the same reason the step number is bounded above: the key is
     * read back by anchored patterns that this ceiling is part of, so a position no step could have
     * produced must be refused where it is rendered rather than written and silently skipped by
     * every reader afterwards.
     */
    @Test
    void aCallOrdinalOutsideTheStepsCallCeilingIsRefusedBeforeAnyRowIsWritten() {
        for (int invalid : new int[] {
                -1, AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS + 1}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.proposeTool(TURN, 4, invalid, "search_records", "{}"),
                    "expected a refusal for call ordinal " + invalid);
        }

        verify(chatMapper, never()).insertToolCall(org.mockito.ArgumentMatchers.any());
    }

    /**
     * A read proposal is now refused above the hard step ceiling, exactly as a write already was.
     *
     * <p>A deliberate tightening this change brings with it: read and write proposals share one
     * key renderer, so the bound the write path has always enforced now covers reads too. The loop
     * cannot reach it — it stops at {@code HARD_MAX_STEPS} before proposing — so this pins the
     * refusal rather than a behaviour the running system relies on, and keeps a future skill plan
     * longer than the ceiling from silently writing a key no reader's anchored pattern accepts.
     */
    @Test
    void aStepNumberAboveTheHardCeilingIsRefusedForAReadProposalAsItAlreadyWasForAWrite() {
        int beyondCeiling = AiChatAgentLoopService.HARD_MAX_STEPS + 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> service.proposeTool(
                        TURN, beyondCeiling, AiAssistantToolCallRef.SOLE_CALL,
                        "search_records", "{}"));

        verify(chatMapper, never()).insertToolCall(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nativeThoughtSignatureSurvivesReadAndWriteProposalPersistence() {
        String thoughtSignature = "opaque /+==\nline two";

        service.proposeTool(
                TURN, 1, 0, "search_records", "{\"query\":\"pipeline\"}",
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

    /**
     * Only a write proposal the server prepared is flagged as server-proposed. A read call or a
     * refusal, which stores what the model sent, never is, so the card projection can tell them
     * apart (#1867).
     */
    @Test
    void onlyAServerPreparedWriteProposalIsFlaggedAsServerProposed() {
        service.proposeTool(TURN, 1, 0, "change_deal_stage",
                "{\"refusedArguments\":{\"tool\":\"change_deal_stage\"}}");
        service.proposeWriteTool(TURN, 2, new AiAssistantPreparedWrite(
                "create_note",
                AiAssistantToolCatalog.ToolTier.AUTO,
                "person",
                31,
                "{\"tool\":\"create_note\",\"target\":{\"kind\":\"person\",\"id\":31}}"));

        ArgumentCaptor<AiChatToolCall> persisted =
                ArgumentCaptor.forClass(AiChatToolCall.class);
        verify(chatMapper, times(2)).insertToolCall(persisted.capture());
        assertFalse(persisted.getAllValues().getFirst().isServerProposal());
        assertTrue(persisted.getAllValues().getLast().isServerProposal());
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

    @Test
    void claimingAQueuedTurnTakesItsRunLeaseAfterTheDurableClaim() {
        storedTurn.setStatus("queued");
        when(chatMapper.markTurnRunning(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(1);
        AiRunLease claimed = lease(3L);
        when(runLeaseService.acquireInCurrentTransaction(eq(LEASE_KEY), any(AiRunLeaseGuard.class)))
                .thenReturn(claimed);

        AiRunLeaseGuard ownership = new AiRunLeaseGuard(Duration.ofSeconds(45));

        assertEquals(claimed, service.markRunning(TURN, ownership));

        InOrder order = inOrder(chatMapper, runLeaseService);
        order.verify(chatMapper).markTurnRunning(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        order.verify(runLeaseService).acquireInCurrentTransaction(LEASE_KEY, ownership);
    }

    @Test
    void aClaimRefusedBeforeItsDurableWriteTakesNoRunLease() {
        storedTurn.setStatus("running");

        assertThrows(
                ConflictException.class,
                () -> service.markRunning(TURN, new AiRunLeaseGuard(Duration.ofSeconds(45))));

        verify(chatMapper, never()).markTurnRunning(anyInt(), anyInt(), anyInt());
        verify(runLeaseService, never()).acquireInCurrentTransaction(any(), any());
    }

    @Test
    void aClaimWhoseDurableWriteMatchesNothingFailsLoudlyAndTakesNoRunLease() {
        storedTurn.setStatus("queued");
        when(chatMapper.markTurnRunning(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.markRunning(TURN, new AiRunLeaseGuard(Duration.ofSeconds(45))));

        verify(runLeaseService, never()).acquireInCurrentTransaction(any(), any());
    }

    @Test
    void everyDurableTerminalWriteTombstonesTheRunLeaseInItsOwnTransaction() {
        when(chatMapper.updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        when(chatMapper.nextMessageSequence(
                TURN.workspaceId(), TURN.sessionId())).thenReturn(2);
        when(chatMapper.cancelTurn(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId())).thenReturn(1);

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.markTerminal(TURN, "failed", "owner_lost"));
            assertTrue(service.resolve(TURN, "Answer", null, 5, 3));
            service.cancel(TURN.sessionId(), TURN.turnId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(runLeaseService, times(3)).releaseHeldInCurrentTransaction(LEASE_KEY);
    }

    @Test
    void aTerminalWriteThatChangedNothingTombstonesNoRunLease() {
        when(chatMapper.updateTurnTerminal(
                anyInt(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(0);

        assertFalse(service.markTerminal(TURN, "failed", "owner_lost"));

        verify(runLeaseService, never()).releaseHeldInCurrentTransaction(any());
    }

    @Test
    void aLazilyExpiredTurnTombstonesItsRunLeaseWithTheSameTerminalWrite() {
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

        assertEquals("timed_out", service.readTurn(TURN.sessionId(), TURN.turnId()).getStatus());

        verify(runLeaseService).releaseHeldInCurrentTransaction(LEASE_KEY);
    }

    private static AiRunLease lease(long epoch) {
        return new AiRunLease(LEASE_KEY, "11111111-2222-3333-4444-555555555555", epoch);
    }

    private static Attachment attachment(int id) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        return attachment;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void queuedReferenceLabelsMaskTheirMentionWithoutChangingSiblingWords(boolean nativeProtocol) throws Exception {
        String text = "Open client and [Li](user:42)";
        var message = queueWithAuthorizedMemberReferences(text);
        var metadata = JsonMapper.builder().build().readTree(message.getStructuredJson());
        assertEquals("mention", metadata.path("memberIdentifiers").get(0).path("matching").asString());
        assertQueuedReferencePayloads(message, nativeProtocol, "Li");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void blankMemberReferencesQueueAndDispatchNormally(boolean nativeProtocol) throws Exception {
        var message = queueWithAuthorizedMemberReferences("Open client and [](user:42) [ ](user:42) [\t](user:42)");
        var metadata = JsonMapper.builder().build().readTree(message.getStructuredJson());
        assertEquals(0, metadata.path("memberIdentifiers").size());
        assertQueuedReferencePayloads(message, nativeProtocol, null);
    }

    private ooo.klae.connex.backend.beans.AiChatMessage queueWithAuthorizedMemberReferences(String content) {
        var references = mock(ooo.klae.connex.backend.services.ReferenceService.class);
        when(references.visibleMemberIds(TURN.workspaceId(), java.util.Set.of(42))).thenReturn(java.util.Set.of(42));
        var scope = mock(ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess.class);
        when(scope.getForWorkspace(TURN.workspaceId())).thenReturn(
                new ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope(
                        1, List.of(TURN.workspaceId()), "[" + TURN.workspaceId() + "]"));
        var member = new User();
        member.setId(42);
        member.setDisplayName("Later Member Name");
        var actor = new User();
        actor.setId(TURN.userId());
        actor.setDisplayName("Current Actor");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(actor, member));
        var resolver = new AiAssistantIdentifierResolver(
                mock(ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper.class), workspaceService, scope, references);
        when(identifierResolver.resolve(content)).thenAnswer(call -> resolver.resolve(content));
        when(chatMapper.nextMessageSequence(TURN.workspaceId(), TURN.sessionId())).thenReturn(2);
        service.queue(TURN.sessionId(), new AiChatTurnCreateRequest(content, List.of()), TURN.restrictionEpoch());
        var message = ArgumentCaptor.forClass(ooo.klae.connex.backend.beans.AiChatMessage.class);
        verify(chatMapper).insertMessage(message.capture());
        return message.getValue();
    }

    private void assertQueuedReferencePayloads(ooo.klae.connex.backend.beans.AiChatMessage message,
            boolean nativeProtocol, String member) throws Exception {
        var mapper = JsonMapper.builder().build();
        var assembler = new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog());
        var support = new AiAssistantMaskingTestSupport(TURN.workspaceId(), TURN.userId());
        try {
            for (int replay = 0; replay < 2; replay++) {
                var context = new ooo.klae.connex.backend.ai.masking.MaskingContext();
                context.registerMemberCandidate(replay == 0 ? "Later Member Name" : "Renamed Again");
                var resources = new AiChatResourceRegistry(context);
                var empty = new AiAssistantToolResult(Map.of(), List.of());
                var prompt = nativeProtocol
                        ? assembler.assembleNative(List.of(message), empty, List.of(), context, resources, List.of(),
                                new AiAssistantPromptBudget(64, 4_096, 8_000, 8_000, 8_000, 64_000), AiAssistantToolCatalog.ALL)
                        : assembler.assemble(List.of(message), empty, List.of(), context, resources, AiAssistantToolCatalog.ALL);
                var capture = support.captureAssistantPayload(prompt, context, mapper, nativeProtocol, List.of(),
                        AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"), false, null);
                String payload = mapper.writeValueAsString(capture.request().messages());
                assertTrue(payload.contains("Open client and"));
                assertFalse(payload.contains("[Li]"));
                if (member != null) {
                    String token = context.tokenBindings().stream().filter(entry -> member.equals(entry.getValue()))
                            .map(Map.Entry::getKey).findFirst().orElseThrow();
                    assertTrue(payload.contains(token));
                    assertEquals(member, ooo.klae.connex.backend.ai.masking.Demasker.demask(token, context).text());
                }
                assertEquals(0, capture.warnings());
            }
        } finally {
            support.releaseFixtureMocks();
        }
    }

    @Test
    void queuedUserMemberProvenanceUsesTheRollbackCompatibleField() {
        when(identifierResolver.resolve("Ask Former Member about Old Company")).thenReturn(
                new AiAssistantIdentifierResolver.Resolution(List.of(), List.of(
                        new AiAssistantIdentifierResolver.Identifier(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Former Member"),
                        new AiAssistantIdentifierResolver.Identifier(ooo.klae.connex.backend.ai.masking.EntityKind.COMPANY, "Old Company"))));
        when(chatMapper.nextMessageSequence(TURN.workspaceId(), TURN.sessionId())).thenReturn(2);
        service.queue(TURN.sessionId(), new AiChatTurnCreateRequest("Ask Former Member about Old Company", List.of()),
                TURN.restrictionEpoch());
        var message = ArgumentCaptor.forClass(ooo.klae.connex.backend.beans.AiChatMessage.class);
        verify(chatMapper).insertMessage(message.capture());
        var metadata = JsonMapper.builder().build().readTree(message.getValue().getStructuredJson());
        assertEquals(1, metadata.path("identifiers").size());
        assertEquals("company", metadata.path("identifiers").get(0).path("kind").asString());
        assertEquals(1, metadata.path("memberIdentifiers").size());
        assertEquals("member", metadata.path("memberIdentifiers").get(0).path("kind").asString());
        assertEquals("Former Member", metadata.path("memberIdentifiers").get(0).path("value").asString());
        assertEquals("mention", metadata.path("memberIdentifiers").get(0).path("matching").asString());
        assertFalse(metadata.toString().contains("{{M"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Ask Former Member to follow up.", "Ask former member to follow up.",
            "Ask Former Member to follow up. ", " Ask Former Member to follow up.", "A different request"})
    void exactLatestSuggestionsCarryTheirProvenanceIntoTheQueuedUserMessage(String content) {
        String suggestion = "Ask Former Member to follow up.";
        var answer = new ooo.klae.connex.backend.beans.AiChatMessage();
        answer.setStructuredJson("{\"suggestions\":[\"" + suggestion
                + "\"],\"memberIdentifiers\":[{\"kind\":\"member\",\"value\":\"Former Member\",\"path\":\"/suggestions/0\"},"
                + "{\"kind\":\"member\",\"value\":\"Unrelated Member\",\"path\":\"/text\"}]}");
        when(chatMapper.getLatestAnswer(TURN.workspaceId(), TURN.sessionId())).thenReturn(answer);
        when(chatMapper.nextMessageSequence(TURN.workspaceId(), TURN.sessionId())).thenReturn(2);
        service.queue(TURN.sessionId(), new AiChatTurnCreateRequest(content, List.of()), TURN.restrictionEpoch());
        var message = ArgumentCaptor.forClass(ooo.klae.connex.backend.beans.AiChatMessage.class);
        InOrder order = inOrder(chatMapper);
        order.verify(chatMapper).getSessionByIdForUpdate(TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(chatMapper).getLatestAnswer(TURN.workspaceId(), TURN.sessionId());
        order.verify(chatMapper).insertMessage(message.capture());
        var metadata = JsonMapper.builder().build().readTree(message.getValue().getStructuredJson());
        assertEquals(0, metadata.path("identifiers").size());
        assertEquals(suggestion.equals(content) ? 1 : 0, metadata.path("memberIdentifiers").size());
        if (suggestion.equals(content)) {
            assertEquals("Former Member", metadata.path("memberIdentifiers").get(0).path("value").asString());
            assertFalse(metadata.path("memberIdentifiers").get(0).has("matching"));
        }
        assertFalse(metadata.toString().contains("{{M"));
        verify(identifierResolver).resolve(content);
    }

    @Test
    void suggestionProvenanceKeepsTheCombinedIdentifierLimitAndExactDeduplication() {
        var answer = new ooo.klae.connex.backend.beans.AiChatMessage();
        String text = "Ask Former Member";
        answer.setStructuredJson("{\"suggestions\":[\"Ask Former Member\"],\"memberIdentifiers\":["
                + "{\"kind\":\"member\",\"value\":\"Former Member\",\"path\":\"/suggestions/0\"}]}");
        when(chatMapper.getLatestAnswer(TURN.workspaceId(), TURN.sessionId())).thenReturn(answer);
        var identifiers = java.util.stream.IntStream.range(0, 20).mapToObj(index ->
                new AiAssistantIdentifierResolver.Identifier(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER,
                        "Other Member " + index)).toList();
        when(identifierResolver.resolve(text)).thenReturn(new AiAssistantIdentifierResolver.Resolution(List.of(), identifiers));
        var failure = assertThrows(AiAssistantLoopException.class, () -> service.queue(TURN.sessionId(),
                new AiChatTurnCreateRequest(text, List.of()), TURN.restrictionEpoch()));
        assertEquals("malformed_output", failure.terminalReason());
        assertEquals("identifier_limit_exceeded", failure.detailReason());
        verify(chatMapper, never()).insertMessage(any());
        when(identifierResolver.resolve(text)).thenReturn(new AiAssistantIdentifierResolver.Resolution(List.of(),
                java.util.Collections.nCopies(20, identifiers.getFirst())));
        assertEquals("identifier_limit_exceeded", assertThrows(AiAssistantLoopException.class,
                () -> service.queue(TURN.sessionId(), new AiChatTurnCreateRequest(text, List.of()), TURN.restrictionEpoch())).detailReason());
        verify(chatMapper, never()).insertMessage(any());
        when(identifierResolver.resolve(text)).thenReturn(new AiAssistantIdentifierResolver.Resolution(List.of(),
                List.of(new AiAssistantIdentifierResolver.Identifier(ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, "Former Member"))));
        service.queue(TURN.sessionId(), new AiChatTurnCreateRequest(text, List.of()), TURN.restrictionEpoch());
        var message = ArgumentCaptor.forClass(ooo.klae.connex.backend.beans.AiChatMessage.class);
        verify(chatMapper).insertMessage(message.capture());
        var members = JsonMapper.builder().build().readTree(message.getValue().getStructuredJson()).path("memberIdentifiers");
        assertEquals(1, members.size());
        assertFalse(members.get(0).has("matching"));
    }

}
