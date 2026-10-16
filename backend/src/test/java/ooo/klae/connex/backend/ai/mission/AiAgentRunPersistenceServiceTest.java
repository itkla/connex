package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.ai.assistant.AiChatQueryScope;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAgentRunPersistenceServiceTest {
    private static final int WORKSPACE = 7;
    private static final int OWNER = 11;
    private static final int SESSION = 13;
    private static final int TURN = 17;
    private static final int GOAL = 19;
    private static final int RUN = 31;
    private static final int SEGMENT = 37;
    private static final long GENERATION = 4;
    private static final long EPOCH = 23;
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String SCOPE = "{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":41}";
    private static final String REQUEST_KEY = "00000000-0000-0000-0000-000000000001";
    private static final AiRunLeaseKey LEASE_KEY = new AiRunLeaseKey(WORKSPACE, AiRunLeaseSubject.AGENT_RUN, RUN);

    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiRestrictionEpoch restriction = mock(AiRestrictionEpoch.class);
    private final AiRunLeaseService leases = mock(AiRunLeaseService.class);
    private final AiAgentCapabilityGate gate = mock(AiAgentCapabilityGate.class);
    private final AiAgentRunPersistenceService proxy = mock(AiAgentRunPersistenceService.class);
    private final AiRunLeaseGuard guard = new AiRunLeaseGuard(Duration.ofMinutes(1));
    private final AiRunLease lease = new AiRunLease(LEASE_KEY, "test", 2);
    private final AiChatSession session = new AiChatSession();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();
    private final AiAgentRun run = new AiAgentRun();
    private final AiAgentSegment segment = new AiAgentSegment();
    private final AiChatTurn turn = new AiChatTurn();
    private final AiChatMessage goal = new AiChatMessage();
    private AiAgentRunPersistenceService service;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("missionPersistence", proxy);
        service = new AiAgentRunPersistenceService(runs, chat, workspaces, restriction, leases,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC),
                factory.getBeanProvider(AiAgentRunPersistenceService.class));
        when(workspaces.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        when(workspaces.getCurrentUserId()).thenReturn(OWNER);
        when(workspaces.isMember(WORKSPACE, OWNER)).thenReturn(true);
        session.setWorkspaceId(WORKSPACE);
        session.setId(SESSION);
        session.setStatus("active");
        session.setCreatedByUserId(OWNER);
        session.setVisibility("private");
        session.setMissionTaintSeq(3);
        session.setMissionTaintGeneration(GENERATION);
        barrier.setWorkspaceId(WORKSPACE);
        barrier.setGeneration(GENERATION);
        when(chat.getSessionByIdForUpdate(WORKSPACE, OWNER, SESSION)).thenReturn(session);
        when(chat.getSessionByIdForMaintenanceUpdate(WORKSPACE, SESSION)).thenReturn(session);
        when(chat.getPrivacySession(WORKSPACE, SESSION)).thenReturn(session);
        when(chat.getAccessibleSessionById(WORKSPACE, OWNER, SESSION)).thenReturn(session);
        when(chat.getPrivacyBarrier(WORKSPACE)).thenReturn(barrier);
        when(chat.lockPrivacyBarrierForShare(WORKSPACE)).thenReturn(barrier);
        when(restriction.current(WORKSPACE)).thenReturn(EPOCH);
        when(restriction.retainReadFenceUntilTransactionCompletionIfCurrent(WORKSPACE, EPOCH)).thenReturn(true);
        run.setWorkspaceId(WORKSPACE);
        run.setId(RUN);
        run.setSessionId(SESSION);
        run.setOwnerUserId(OWNER);
        run.setClientRequestKey(REQUEST_KEY);
        run.setRequestHash(AiAgentCapabilityGate.hash("request"));
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(1);
        run.setLocale("en");
        run.setScopeJson(SCOPE);
        run.setStatus("running");
        run.setStateVersion(2);
        run.setExecutionGeneration(1);
        run.setActiveSegmentId(SEGMENT);
        run.setPrivacyGeneration(GENERATION);
        run.setMaxSteps(8);
        run.setMaxTokens(131072);
        run.setExecutionDeadlineAt(now().plusSeconds(900));
        run.setHardDeadlineAt(run.getExecutionDeadlineAt());
        segment.setId(SEGMENT);
        segment.setWorkspaceId(WORKSPACE);
        segment.setRunId(RUN);
        segment.setTurnId(TURN);
        segment.setStatus("running");
        segment.setExecutionGeneration(1);
        segment.setReservedSteps(8);
        segment.setReservedTokens(131072);
        turn.setId(TURN);
        turn.setWorkspaceId(WORKSPACE);
        turn.setSessionId(SESSION);
        turn.setMissionRunId(RUN);
        turn.setMissionSegmentId(SEGMENT);
        turn.setStatus("running");
        goal.setId(GOAL);
        goal.setSeq(3);
        goal.setSessionId(SESSION);
        goal.setWorkspaceId(WORKSPACE);
        when(runs.getRun(WORKSPACE, RUN)).thenReturn(run);
        when(runs.lockRun(WORKSPACE, RUN)).thenReturn(run);
        when(runs.getSegment(WORKSPACE, SEGMENT)).thenReturn(segment);
        when(runs.lockSegment(WORKSPACE, SEGMENT)).thenReturn(segment);
        when(runs.updateRun(any(), anyLong(), anyLong())).thenReturn(1);
        when(runs.updateSegment(any(), eq("running"))).thenReturn(1);
        when(chat.getTurnByIdForUpdate(WORKSPACE, SESSION, TURN)).thenReturn(turn);
        when(chat.getMissionMessage(WORKSPACE, RUN, "goal")).thenReturn(goal);
        when(leases.acquireInCurrentTransaction(eq(LEASE_KEY), any())).thenReturn(lease);
        doAnswer(invocation -> {
            AiChatTurn inserted = invocation.getArgument(0);
            inserted.setId(TURN);
            return null;
        }).when(chat).insertTurn(any());
        doAnswer(invocation -> {
            AiAgentSegment inserted = invocation.getArgument(0);
            inserted.setId(SEGMENT);
            return 1;
        }).when(runs).insertSegment(any());
    }

    @Test
    void replaysCreateWithoutAnotherGoalOrDeadline() {
        when(runs.findByRequestKey(WORKSPACE, OWNER, REQUEST_KEY)).thenReturn(run);
        LocalDateTime deadline = run.getExecutionDeadlineAt();

        assertSame(run, service.create(SESSION, request(), "en", run.getRequestHash(), SCOPE, EPOCH));
        assertEquals(deadline, run.getExecutionDeadlineAt());
        verify(chat, never()).nextMessageSequence(anyInt(), anyInt());
        verify(chat, never()).insertMessage(any());
        verify(runs, never()).insertRun(any());
        assertThrows(ConflictException.class, () -> service.create(SESSION, request(), "en",
                AiAgentCapabilityGate.hash("different request"), SCOPE, EPOCH));
    }

    @Test
    void purgedCreateKeyCannotCreateAgainAfterRequestHashErasure() {
        run.setPurgedAt(now());
        run.setRequestHash(null);
        when(runs.findByRequestKey(WORKSPACE, OWNER, REQUEST_KEY)).thenReturn(run);

        assertThrows(AiAgentGoneException.class, () -> service.create(SESSION, request(), "en",
                AiAgentCapabilityGate.hash("request"), SCOPE, EPOCH));
        verify(chat, never()).nextMessageSequence(anyInt(), anyInt());
        verify(runs, never()).insertRun(any());
    }

    @Test
    void firstMissionRefusesActiveReactiveTurnBeforeTaintOrGoalAllocation() {
        session.setMissionTaintSeq(null);
        session.setMissionTaintGeneration(null);
        when(chat.countActiveTurns(WORKSPACE, SESSION)).thenReturn(1);

        ConflictException failure = assertThrows(ConflictException.class, () -> service.create(SESSION,
                request(), "en", AiAgentCapabilityGate.hash("request"), SCOPE, EPOCH));

        assertEquals("mission_session_busy", failure.getMessage());
        assertNull(session.getMissionTaintSeq());
        verify(chat, never()).nextMessageSequence(anyInt(), anyInt());
        verify(chat, never()).insertMessage(any());
        verify(runs, never()).insertRun(any());
    }

    @Test
    void claimUsesOnlyAgentLeaseAfterFinalPrivacyFenceAndReturnsTransactionalProxy() {
        run.setStatus("queued");
        run.setActiveSegmentId(null);
        run.setConsumedSteps(2);
        run.setConsumedTokens(8192);

        AiAgentExecutionPolicy policy = service.claim(run, new byte[32], guard, gate);

        assertSame(proxy, policy.persistence());
        assertEquals(6, policy.maxSteps());
        assertEquals(122880, run.getReservedTokens());
        assertEquals(NOW.plusSeconds(180), policy.deadline());
        assertEquals(GENERATION, policy.turn().privacyToken().privacyGeneration());
        assertEquals(3, policy.turn().privacyToken().taintSeqAtAdmission());
        assertEquals(lease, policy.lease());
        InOrder order = inOrder(chat, runs, leases);
        order.verify(chat).getSessionByIdForUpdate(WORKSPACE, OWNER, SESSION);
        order.verify(runs).lockRun(WORKSPACE, RUN);
        order.verify(chat).insertTurn(any());
        order.verify(runs).insertSegment(any());
        order.verify(chat).linkMissionTurn(WORKSPACE, SESSION, TURN, RUN, SEGMENT);
        order.verify(runs).updateRun(run, 2, 1);
        order.verify(chat).lockPrivacyBarrierForShare(WORKSPACE);
        order.verify(leases).acquireInCurrentTransaction(LEASE_KEY, guard);
        order.verifyNoMoreInteractions();
    }

    @Test
    void stalePrivacyRefusesClaimBeforeAttemptOrAllowance() {
        run.setStatus("queued");
        run.setActiveSegmentId(null);
        barrier.setGeneration(GENERATION + 1);
        barrier.setBlocked(false);

        assertThrows(AiAssistantLoopException.class, () -> service.claim(run, new byte[32], guard, gate));

        assertEquals(1, run.getExecutionGeneration());
        assertEquals(0, run.getReservedSteps());
        verify(chat, never()).insertTurn(any());
        verify(runs, never()).insertSegment(any());
        verifyNoInteractions(leases);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void incompatibleRuntimeCannotClaim(int version) {
        run.setStatus("queued");
        run.setRuntimeContractVersion(version);

        assertThrows(ConflictException.class, () -> service.claim(run, new byte[32], guard, gate));
        verify(chat, never()).insertTurn(any());
        verifyNoInteractions(leases);
    }

    @Test
    void oldAttemptCannotPublishOrReleaseSuccessor() {
        AiAgentExecutionPolicy old = policy();
        run.setExecutionGeneration(2);
        run.setActiveSegmentId(38);

        assertThrows(ConflictException.class, () -> service.resolve(old, "Grounded report", "{}"));
        verify(chat, never()).insertMessage(any());
        verify(chat, never()).nextMessageSequence(anyInt(), anyInt());
        verifyNoInteractions(leases);
        assertEquals(38, run.getActiveSegmentId());
        assertEquals("running", run.getStatus());
    }

    @Test
    void uncertainDispatchCannotStartFallbackOrAnotherCall() {
        AiAgentExecutionPolicy policy = policy();
        service.reserveDispatch(policy, 8192);

        assertEquals(1, segment.getKnownSteps());
        assertTrue(segment.isUsageUncertain());
        assertThrows(AiAssistantLoopException.class, () -> service.reserveDispatch(policy, 8192));
        assertEquals(1, segment.getKnownSteps());
    }

    @Test
    void knownUsageSettlesUncertaintyAndAccumulatesBeforeNextDispatch() {
        AiAgentExecutionPolicy policy = policy();
        service.reserveDispatch(policy, 8192);
        service.recordUsage(policy, 2048, 512);
        service.reserveDispatch(policy, 8192);

        assertEquals(2, segment.getKnownSteps());
        assertEquals(2048, segment.getKnownInputTokens());
        assertEquals(512, segment.getKnownOutputTokens());
        assertTrue(segment.isUsageUncertain());
    }

    @Test
    void interruptedUnknownUsageChargesFullReservationBeforeFinalLeaseRelease() {
        segment.setUsageUncertain(true);
        segment.setKnownSteps(1);
        service.interrupt(run, "interrupted");

        assertEquals("paused", run.getStatus());
        assertEquals(8, run.getConsumedSteps());
        assertEquals(131072, run.getConsumedTokens());
        assertNull(run.getActiveSegmentId());
        InOrder order = inOrder(chat, runs, leases);
        order.verify(chat).getSessionByIdForMaintenanceUpdate(WORKSPACE, SESSION);
        order.verify(runs).lockRun(WORKSPACE, RUN);
        order.verify(chat).getTurnByIdForUpdate(WORKSPACE, SESSION, TURN);
        order.verify(runs).lockSegment(WORKSPACE, SEGMENT);
        order.verify(chat).clearTurnContent(WORKSPACE, SESSION, TURN);
        order.verify(chat).updateTurnTerminal(WORKSPACE, SESSION, TURN, "cancelled", "interrupted", "running", null);
        order.verify(runs).updateSegment(segment, "running");
        order.verify(runs).updateRun(run, 2, 1);
        order.verify(leases).releaseHeldInCurrentTransaction(LEASE_KEY);
        order.verifyNoMoreInteractions();
    }

    @Test
    void successorCannotMutatePriorAttemptToolResultByKnownId() {
        AiChatToolCall call = new AiChatToolCall();
        call.setId(47);
        call.setMissionRunId(RUN);
        call.setMessageId(GOAL);
        call.setArtifactOrigin("mission");
        call.setStatus("proposed");
        call.setIdempotencyKey("mission-31-36-1-0");
        when(chat.getToolCallBySessionForUpdate(WORKSPACE, SESSION, 47)).thenReturn(call);

        assertThrows(ConflictException.class, () -> service.finishTool(policy(), 47, "executed", "{}"));

        verify(chat, never()).updateToolCall(anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
        verify(runs, never()).lockSegment(WORKSPACE, SEGMENT);
    }

    @Test
    void toolResultLocksCanonicalToolBeforeTurnAndSegment() {
        AiChatToolCall call = new AiChatToolCall();
        call.setId(47);
        call.setMissionRunId(RUN);
        call.setMessageId(GOAL);
        call.setArtifactOrigin("mission");
        call.setStatus("proposed");
        call.setIdempotencyKey("mission-31-37-1-0");
        when(chat.getToolCallBySessionForUpdate(WORKSPACE, SESSION, 47)).thenReturn(call);
        when(chat.updateToolCall(WORKSPACE, GOAL, 47, "executed", "{}", OWNER)).thenReturn(1);

        assertTrue(service.finishTool(policy(), 47, "executed", "{}"));

        InOrder order = inOrder(chat, runs);
        order.verify(chat).getSessionByIdForUpdate(WORKSPACE, OWNER, SESSION);
        order.verify(runs).lockRun(WORKSPACE, RUN);
        order.verify(chat).getToolCallBySessionForUpdate(WORKSPACE, SESSION, 47);
        order.verify(chat).getTurnByIdForUpdate(WORKSPACE, SESSION, TURN);
        order.verify(runs).lockSegment(WORKSPACE, SEGMENT);
        order.verify(chat).updateToolCall(WORKSPACE, GOAL, 47, "executed", "{}", OWNER);
        order.verify(chat).lockPrivacyBarrierForShare(WORKSPACE);
    }

    @Test
    void resumeKeepsOriginalCapsDeadlineAndConsumedAllowance() {
        run.setStatus("paused");
        run.setActiveSegmentId(null);
        run.setConsumedSteps(2);
        run.setConsumedTokens(4096);
        LocalDateTime deadline = run.getExecutionDeadlineAt();

        service.control(run, 2, "resume");

        assertEquals("queued", run.getStatus());
        assertEquals(8, run.getMaxSteps());
        assertEquals(131072, run.getMaxTokens());
        assertEquals(2, run.getConsumedSteps());
        assertEquals(4096, run.getConsumedTokens());
        assertEquals(deadline, run.getExecutionDeadlineAt());
        assertEquals(deadline, run.getHardDeadlineAt());
        verifyNoInteractions(leases);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 12})
    void dispatchRevalidationRefusesMissingOrDepartedSessionCreator(int creatorId) {
        session.setVisibility("shared");
        session.setCreatedByUserId(creatorId == 0 ? null : creatorId);

        assertThrows(ResourceNotFoundException.class, () -> service.requireRunning(policy()));

        verify(chat).getAccessibleSessionById(WORKSPACE, OWNER, SESSION);
        verify(chat, never()).getSessionByIdForUpdate(anyInt(), anyInt(), anyInt());
        verify(runs, never()).lockRun(anyInt(), anyInt());
        verifyNoInteractions(leases);
    }

    @Test
    void thirdAttemptAndDeadlineAreStrictResumeBounds() {
        run.setStatus("paused");
        run.setActiveSegmentId(null);
        when(runs.listSegments(WORKSPACE, RUN)).thenReturn(List.of(segment, segment, segment));
        assertEquals("mission_attempt_limit", assertThrows(ConflictException.class,
                () -> service.control(run, 2, "resume")).getMessage());
        run.setExecutionDeadlineAt(now());
        assertEquals("mission_expired", assertThrows(ConflictException.class,
                () -> service.control(run, 2, "resume")).getMessage());
        assertEquals("paused", run.getStatus());
        verify(runs, never()).updateRun(any(), anyLong(), anyLong());
    }

    private AiAgentExecutionPolicy policy() {
        AiChatQueuedTurn queued = new AiChatQueuedTurn(WORKSPACE, OWNER, SESSION, TURN, GOAL, 3,
                EPOCH, false, List.of(), List.of(), AiPrivacyMode.MASKED, false,
                AiChatQueryScope.none(), new AiChatPrivacyToken(WORKSPACE, SESSION, GENERATION, 3));
        return new AiAgentExecutionPolicy(RUN, SEGMENT, 1, queued, goal, new AiAgentScopePolicy("person", 41),
                8, NOW.plusSeconds(180), lease, guard, proxy, gate);
    }

    private static AiAgentRunCreateRequest request() {
        return new AiAgentRunCreateRequest(REQUEST_KEY, "record_review_v1", "Review this record",
                new AiAgentRunCreateRequest.Scope("record", "person", 41), null);
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }
}
