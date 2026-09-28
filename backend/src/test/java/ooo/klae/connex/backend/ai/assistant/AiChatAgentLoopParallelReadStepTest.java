package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiNativeToolCompletion;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.AiStructuredRepairAttempt;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseHeartbeat;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules a batched native step runs under, isolated from the scripted goldens that exercise it
 * end to end.
 *
 * <p>Every turn here runs against an endpoint that declared the widest call bound, so the loop
 * asks for a batch and admits one the provider returns. What these tests pin is what the goldens
 * cannot isolate: the order of whole-step admission and its two refusal shapes, the per-call
 * ownership, deadline and authorization checkpoints, whole-step replay atomicity when a batch is
 * abandoned part-way, the sparse durable ordinals a cache hit leaves, and that a batch is charged
 * as one model decision to every step budget and to the no-progress guard.
 */
class AiChatAgentLoopParallelReadStepTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final AiRunLease LEASE = new AiRunLease(
            new AiRunLeaseKey(TURN.workspaceId(), AiRunLeaseSubject.CHAT_TURN, TURN.turnId()),
            "11111111-2222-3333-4444-555555555555",
            1L);
    private static final AiAssistantPromptBudget BUDGET =
            new AiAssistantPromptBudget(64, 64_000, 16_000, 16_000, 16_000, 112_000);

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AiInvocationService invocationService;
    private AiInvocationAdmissionService.DirectAdmission directAdmission;
    private AiAssistantToolExecutor toolExecutor;
    private AiAssistantWriteToolService writeToolService;
    private AiChatMemoryService memoryService;
    private AiChatTurnPersistenceService persistenceService;
    private AiRunLeaseHeartbeat runLeaseHeartbeat;
    private WorkspaceService workspaceService;
    private AiWorkspaceGovernanceService governanceService;
    private AiSkillRouter skillRouter;
    private AiSkillPlanRunner skillPlanRunner;
    private Clock clock;
    private AiAssistantPromptAssembler promptAssembler;
    private AiChatAgentLoopService service;

    @BeforeEach
    void setUp() {
        invocationService = mock(AiInvocationService.class);
        AiInvocationAdmissionService invocationAdmissionService =
                mock(AiInvocationAdmissionService.class);
        directAdmission = mock(AiInvocationAdmissionService.DirectAdmission.class);
        toolExecutor = mock(AiAssistantToolExecutor.class);
        writeToolService = mock(AiAssistantWriteToolService.class);
        memoryService = mock(AiChatMemoryService.class);
        AiChatAttachmentContextService attachmentContextService =
                mock(AiChatAttachmentContextService.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        runLeaseHeartbeat = mock(AiRunLeaseHeartbeat.class);
        AiRunLeaseService runLeaseService = mock(AiRunLeaseService.class);
        AiChatProgressService progressService = mock(AiChatProgressService.class);
        AiRestrictionEpoch restrictionEpoch = mock(AiRestrictionEpoch.class);
        workspaceService = mock(WorkspaceService.class);
        governanceService = mock(AiWorkspaceGovernanceService.class);
        skillRouter = mock(AiSkillRouter.class);
        skillPlanRunner = mock(AiSkillPlanRunner.class);
        clock = mock(Clock.class);
        when(skillRouter.route(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(AiSkillRouter.Routing.fallback("no_matching_skill"));
        when(invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT))
                .thenReturn(new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.JSON_SCHEMA,
                        AiReasoningMode.TAGGED,
                        AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS,
                        8_192));
        AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
        promptAssembler = spy(new AiAssistantPromptAssembler(objectMapper, catalog));
        service = new AiChatAgentLoopService(
                invocationService,
                invocationAdmissionService,
                new AiProperties(),
                new AiAssistantStepGuard(catalog),
                catalog,
                new AiAssistantStepSchema(objectMapper, catalog),
                toolExecutor,
                new AiAssistantToolsetLoader(catalog),
                writeToolService,
                promptAssembler,
                skillRouter,
                skillPlanRunner,
                memoryService,
                attachmentContextService,
                persistenceService,
                runLeaseHeartbeat,
                runLeaseService,
                progressService,
                mock(AiChatCitationProjector.class),
                restrictionEpoch,
                workspaceService,
                objectMapper,
                mock(AiChatRealtimeDispatcher.class),
                governanceService,
                clock);
        when(persistenceService.markRunning(eq(TURN), any(AiRunLeaseGuard.class)))
                .thenReturn(LEASE);
        when(runLeaseHeartbeat.start(any(), any())).thenReturn(() -> { });
        when(progressService.project(anyInt(), anyInt(), anyInt(), any())).thenReturn(List.of());
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId())).thenReturn(true);
        doReturn(directAdmission).when(invocationAdmissionService).acquireDirect();
        useBudget(BUDGET);
        when(attachmentContextService.prepare(eq(TURN), any(Instant.class), any(), any()))
                .thenReturn(AiChatAttachmentContext.empty());
        when(toolExecutor.pageContext(any(), any()))
                .thenReturn(new AiAssistantToolResult(Map.of(), List.of()));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class), any()))
                .thenAnswer(invocation -> resultFor(invocation.getArgument(1)));
        when(persistenceService.proposeTool(eq(TURN), anyInt(), anyInt(), any(), any()))
                .thenReturn(29);
        when(persistenceService.proposeTool(eq(TURN), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(29);
        when(persistenceService.finishTool(eq(TURN), anyInt(), any(), any())).thenReturn(true);
        when(restrictionEpoch.current(TURN.workspaceId())).thenReturn(TURN.restrictionEpoch());
        when(governanceService.isEnabled(TURN.workspaceId())).thenReturn(true);
        when(governanceService.assistantMaxSteps(TURN.workspaceId())).thenReturn(6);
        when(clock.instant()).thenReturn(NOW);
    }

    /** A declared endpoint is asked for the batch the loop can now execute, and no more. */
    @Test
    void aDeclaredEndpointIsAskedForTheBatchTheLoopCanExecute() throws Exception {
        answers(finalAnswer());

        service.run(TURN);

        assertEquals(
                AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS,
                AiChatAgentLoopService.MAX_EXECUTABLE_CALLS_PER_STEP);
        assertEquals(
                AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS,
                requests().getFirst().maxParallelCalls());
    }

    /**
     * An admitted batch runs every call, in provider order, as one replayed step.
     *
     * <p>Each call gets its own suffixed durable row and its own execution, and the next request
     * replays them as one run of exchanges sharing the step, ordinals dense from 1, each carrying
     * its own provider id and its own thought signature byte for byte.
     */
    @Test
    void anAdmittedBatchRunsEveryCallInOrderAndReplaysThemAsOneStep() throws Exception {
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("alpha"), "sig-1"),
                        new AiToolCall("call_2", "search_records", search("beta"), "sig-2"),
                        new AiToolCall("call_3", "search_records", search("gamma"), null)),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeTool(
                TURN, 1, 1, "search_records", search("alpha"), "sig-1");
        verify(persistenceService).proposeTool(
                TURN, 1, 2, "search_records", search("beta"), "sig-2");
        verify(persistenceService).proposeTool(TURN, 1, 3, "search_records", search("gamma"));
        verify(toolExecutor, times(3)).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true), any());
        verify(persistenceService, times(3)).finishTool(eq(TURN), eq(29), eq("executed"), any());
        List<AiToolExchange> replayed = requests().getLast().exchanges();
        assertEquals(List.of(1, 1, 1), replayed.stream().map(AiToolExchange::step).toList());
        assertEquals(
                List.of(1, 2, 3), replayed.stream().map(AiToolExchange::callOrdinal).toList());
        assertEquals(
                List.of("call_1", "call_2", "call_3"),
                replayed.stream().map(exchange -> exchange.call().id()).toList());
        assertEquals(
                Arrays.asList("sig-1", "sig-2", null),
                replayed.stream().map(exchange -> exchange.call().thoughtSignature()).toList());
    }

    /**
     * An unauthorized write paired with a read still ends the turn as a skill-authority violation.
     *
     * <p>Skill authority runs for every call before any narrowing rule. Admission first would turn
     * this unloaded, unauthorized write into a recoverable {@code tool_not_loaded} — or, loaded, a
     * recoverable {@code mixed_tier_step} — and so launder a turn-ending authority signal into one
     * the model may simply retry.
     */
    @Test
    void anUnauthorizedWritePairedWithAReadStillEndsTheTurnOutsideSkillAuthority()
            throws Exception {
        routedReadSkill();
        answers(batch(
                new AiToolCall("call_1", "search_records", search("alpha")),
                new AiToolCall(
                        "call_2", "create_note",
                        "{\"handle\":\"r1\",\"content\":\"Follow up\"}")));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("tool_outside_skill_authority", result.reason());
        verifyNoToolRowAndNoExecution();
    }

    /**
     * A batch naming an unloaded tool is refused whole, and leaves no replayed exchange at all.
     *
     * <p>Every call still gets its durable failed row, so the transcript records what the model
     * asked for; none executes, and the step contributes no assistant message, because replaying a
     * name the next request's definitions exclude would fail that request's membership check.
     */
    @Test
    void aBatchNamingAnUnloadedToolIsRefusedWholeWithNoReplayedExchange() throws Exception {
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("alpha")),
                        new AiToolCall(
                                "call_2", "aggregate_metric", "{\"metric\":\"deal_kpis\"}")),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeTool(TURN, 1, 1, "search_records", search("alpha"));
        verify(persistenceService).proposeTool(
                TURN, 1, 2, "aggregate_metric", "{\"metric\":\"deal_kpis\"}");
        verify(persistenceService, times(2)).failTool(
                TURN, 29, "{\"reason\":\"tool_not_loaded\"}");
        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class), any());
        assertEquals(List.of(), requests().getLast().exchanges());
    }

    /**
     * A batch carrying a write is refused whole as {@code mixed_tier_step}, before anything runs.
     *
     * <p>The batch also carries {@code find_tools}, so this pins that the tier rule is decided
     * first. Nothing is prepared, proposed as a write or executed, and both refusals are replayed
     * as answers to their own calls so the model learns why and can re-emit the write alone.
     */
    @Test
    void aBatchCarryingAWriteIsRefusedWholeAsMixedTierBeforeAnythingRuns() throws Exception {
        answers(
                load("call_0", "write_content"),
                batch(
                        new AiToolCall(
                                "call_1", AiAssistantToolCatalog.FIND_TOOLS,
                                "{\"toolset\":\"analytics\"}"),
                        new AiToolCall(
                                "call_2", "create_note",
                                "{\"handle\":\"r1\",\"content\":\"Follow up\"}")),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(writeToolService, never()).prepare(any(), any(), any(), anyLong());
        verify(persistenceService, never()).proposeWriteTool(any(), anyInt(), any());
        verify(persistenceService, never()).proposeWriteTool(any(), anyInt(), any(), any());
        verify(persistenceService, times(2)).failTool(
                TURN, 29, "{\"reason\":\"mixed_tier_step\"}");
        assertRefusedStepReplayed(requests().getLast(), 2, "mixed_tier_step");
        assertFalse(definitionNames(requests().getLast()).contains("aggregate_metric"),
                "a refused find_tools must not widen the loaded set");
    }

    /**
     * {@code find_tools} beside other calls is refused whole, before the duplicate rule.
     *
     * <p>The batch also names one read twice, so this pins that {@code find_tools_alone} is
     * decided first; and the loaded set stays unwidened, because a widening mid-step would run the
     * rest of the step under a vocabulary the guard never admitted.
     */
    @Test
    void aBatchCarryingFindToolsIsRefusedWholeBeforeTheDuplicateRule() throws Exception {
        answers(
                batch(
                        new AiToolCall(
                                "call_1", AiAssistantToolCatalog.FIND_TOOLS,
                                "{\"toolset\":\"analytics\"}"),
                        new AiToolCall("call_2", "search_records", search("alpha")),
                        new AiToolCall("call_3", "search_records", search("alpha"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService, times(3)).failTool(
                TURN, 29, "{\"reason\":\"find_tools_alone\"}");
        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class), any());
        assertRefusedStepReplayed(requests().getLast(), 3, "find_tools_alone");
        assertFalse(definitionNames(requests().getLast()).contains("aggregate_metric"));
    }

    /** A batch naming one call twice, whatever its key order, is refused whole. */
    @Test
    void aBatchNamingOneCallTwiceIsRefusedWholeAsADuplicate() throws Exception {
        answers(
                batch(
                        new AiToolCall(
                                "call_1", "search_records",
                                "{\"query\":\"alpha\",\"kinds\":[\"deal\"]}"),
                        new AiToolCall(
                                "call_2", "search_records",
                                "{\"kinds\":[\"deal\"],\"query\":\"alpha\"}")),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService, times(2)).failTool(
                TURN, 29, "{\"reason\":\"duplicate_parallel_call\"}");
        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class), any());
        assertRefusedStepReplayed(requests().getLast(), 2, "duplicate_parallel_call");
    }

    /**
     * A stop signal between two calls halts the batch before the second call writes anything.
     *
     * <p>Ownership is re-polled per call, not once per step, and a lost owner returns before
     * proposing, so a turn the loop no longer owns gains no durable row.
     */
    @Test
    void aStopSignalBetweenTwoCallsHaltsTheBatchBeforeTheSecondCallsRow() throws Exception {
        AtomicReference<AiRunLeaseGuard> guard = new AtomicReference<>();
        when(runLeaseHeartbeat.start(eq(LEASE), any(AiRunLeaseGuard.class)))
                .thenAnswer(invocation -> {
                    guard.set(invocation.getArgument(1));
                    return (AutoCloseable) () -> { };
                });
        doAnswer(invocation -> {
            guard.get().stop(AiRunLeaseGuard.LEASE_LOST);
            return resultFor(invocation.getArgument(1));
        }).when(toolExecutor).execute(any(), any(), any(), any(Boolean.class), any());
        answers(twoReads());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals(AiAssistantTerminalReasons.OWNER_LOST, result.reason());
        verifySecondCallNeverProposed();
    }

    /** A deadline crossed between two calls times the turn out before the second call's row. */
    @Test
    void aDeadlineCrossedBetweenTwoCallsTimesTheTurnOutBeforeTheSecondCallsRow()
            throws Exception {
        doAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plus(AiAssistantTurnBudget.TURN));
            return resultFor(invocation.getArgument(1));
        }).when(toolExecutor).execute(any(), any(), any(), any(Boolean.class), any());
        answers(twoReads());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.TIMED_OUT, result.outcome());
        assertEquals("turn_deadline_exceeded", result.reason());
        verifySecondCallNeverProposed();
    }

    /**
     * Access withdrawn between two calls ends the turn, and the first call keeps its row.
     *
     * <p>Authorization is re-read per call. The call that already ran committed its durable row
     * in its own transaction and really happened, so it stays executed.
     */
    @Test
    void accessWithdrawnBetweenTwoCallsEndsTheTurnWhileTheFirstKeepsItsRow() throws Exception {
        AtomicBoolean member = new AtomicBoolean(true);
        when(workspaceService.isMember(TURN.workspaceId(), TURN.userId()))
                .thenAnswer(invocation -> member.get());
        doAnswer(invocation -> {
            member.set(false);
            return resultFor(invocation.getArgument(1));
        }).when(toolExecutor).execute(any(), any(), any(), any(Boolean.class), any());
        answers(twoReads());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("access_revoked", result.reason());
        verify(persistenceService).finishTool(eq(TURN), eq(29), eq("executed"), any());
        verifySecondCallNeverProposed();
    }

    /**
     * A batch whose third call cannot be admitted to the replay is dropped from the replay whole.
     *
     * <p>The third call settles as {@code tool_result_budget_exhausted} — the closable refusal the
     * replay budget raises when a result cannot fit — the fourth never runs, and the closing
     * request carries no exchange from the step at all rather than an assistant message with two
     * of the four calls the model emitted. The refusal is raised at the third call's execution so
     * the abandonment, not the budget arithmetic the assembler's own tests own, is what is pinned.
     */
    @Test
    void aBudgetRefusalOnTheThirdOfFourCallsDropsTheWholeStepFromTheReplay() throws Exception {
        doAnswer(invocation -> {
            JsonNode arguments = invocation.getArgument(1);
            if ("gamma".equals(arguments.path("query").asString())) {
                throw new AiAssistantLoopException(
                        "tool_result_budget_exhausted", "tool_result_budget_exhausted");
            }
            return resultFor(arguments);
        }).when(toolExecutor).execute(any(), any(), any(), any(Boolean.class), any());
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("alpha")),
                        new AiToolCall("call_2", "search_records", search("beta")),
                        new AiToolCall("call_3", "search_records", search("gamma")),
                        new AiToolCall("call_4", "search_records", search("delta"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(toolExecutor, times(3)).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true), any());
        verify(persistenceService, never()).proposeTool(
                eq(TURN), eq(1), eq(4), anyString(), anyString());
        verify(persistenceService).failTool(
                TURN, 29, "{\"reason\":\"tool_result_budget_exhausted\"}");
        AiNativeToolRequest closing = requests().getLast();
        assertTrue(closing.finalOnly(), "an abandoned batch must go to the closing step");
        assertEquals(List.of(), closing.exchanges(),
                "no call of an abandoned step may be replayed");
    }

    /**
     * A cache hit inside a batch writes no row, so durable ordinals are sparse while the replay's
     * stay dense.
     */
    @Test
    void aCacheHitInsideABatchLeavesADurableGapButADenseReplay() throws Exception {
        answers(
                nativeTool("call_0", "search_records", search("alpha")),
                batch(
                        new AiToolCall("call_1", "search_records", search("beta")),
                        new AiToolCall("call_2", "search_records", search("alpha")),
                        new AiToolCall("call_3", "search_records", search("gamma"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeTool(TURN, 2, 1, "search_records", search("beta"));
        verify(persistenceService, never()).proposeTool(
                eq(TURN), eq(2), eq(2), anyString(), anyString());
        verify(persistenceService).proposeTool(TURN, 2, 3, "search_records", search("gamma"));
        List<AiToolExchange> replayed = requests().getLast().exchanges();
        assertEquals(List.of(1, 2, 2, 2), replayed.stream().map(AiToolExchange::step).toList());
        assertEquals(
                List.of(0, 1, 2, 3), replayed.stream().map(AiToolExchange::callOrdinal).toList());
    }

    /**
     * A batch costs one step against every step budget.
     *
     * <p>With three steps allowed, the second step is still an ordinary one after a three-call
     * batch; charged per call, the batch would have exhausted the budget and made it the closing
     * step, where a tool call ends the turn.
     */
    @Test
    void aBatchIsChargedAsOneStep() throws Exception {
        when(governanceService.assistantMaxSteps(TURN.workspaceId())).thenReturn(3);
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("alpha")),
                        new AiToolCall("call_2", "search_records", search("beta")),
                        new AiToolCall("call_3", "search_records", search("gamma"))),
                nativeTool("call_4", "search_records", search("delta")),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeTool(TURN, 2, 0, "search_records", search("delta"));
        List<AiNativeToolRequest> requests = requests();
        assertEquals(List.of(false, false, true),
                requests.stream().map(AiNativeToolRequest::finalOnly).toList());
    }

    /**
     * One fresh result anywhere in a batch resets the no-progress guard.
     *
     * <p>The step before carried a refusal, so the guard stands at one. The batch's first call
     * repeats evidence the turn already has and its second is fresh: counted per call the first
     * would close the turn, counted per batch the guard resets and the next refusal is survivable.
     */
    @Test
    void oneFreshResultInABatchResetsTheNoProgressGuard() throws Exception {
        answers(
                nativeTool("call_0", "search_records", search("alpha")),
                duplicateBatch("call_1", "call_2"),
                batch(
                        new AiToolCall("call_3", "search_records", search("alpha-again")),
                        new AiToolCall("call_4", "search_records", search("beta"))),
                duplicateBatch("call_5", "call_6"),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        assertEquals(List.of(false, false, false, false, false),
                requests().stream().map(AiNativeToolRequest::finalOnly).toList());
    }

    /** A batch of nothing but repeated evidence costs the guard one unit, not one per call. */
    @Test
    void aBatchOfRepeatedEvidenceCostsTheGuardOneUnit() throws Exception {
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("alpha")),
                        new AiToolCall("call_2", "search_records", search("beta"))),
                batch(
                        new AiToolCall("call_3", "search_records", search("alpha-again")),
                        new AiToolCall("call_4", "search_records", search("beta-again"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        assertEquals(List.of(false, false, false),
                requests().stream().map(AiNativeToolRequest::finalOnly).toList());
    }

    /**
     * A batch of plans alone leaves the no-progress guard exactly where it found it.
     *
     * <p>A refusal sets the guard to one; a plans-only batch must neither reset it — the next
     * refusal then closes the turn — nor advance it, which would have closed the turn at once.
     */
    @Test
    void aBatchOfPlansAloneLeavesTheNoProgressGuardUnchanged() throws Exception {
        answers(
                duplicateBatch("call_1", "call_2"),
                batch(
                        new AiToolCall(
                                "call_3", "set_todos", "{\"items\":[\"Read the deal\"]}"),
                        new AiToolCall(
                                "call_4", "set_todos", "{\"items\":[\"Read the contact\"]}")),
                duplicateBatch("call_5", "call_6"),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        assertEquals(List.of(false, false, false, true),
                requests().stream().map(AiNativeToolRequest::finalOnly).toList());
    }

    /**
     * A later call of a batch cannot name a handle an earlier call of the same batch minted.
     *
     * <p>The model emitted both calls as one decision against an empty registry, so {@code r1}
     * was never shown to it: the search mints it while the batch runs, and the read naming it
     * guessed it. Resolved against the live registry, the guess would execute a read of a record
     * the model had never seen; emitted alone it would settle as {@code unknown_handle}. Resolved
     * against the handles issued when the batch was admitted, the read settles as that refusal,
     * is never executed, and the search beside it still runs and is replayed.
     */
    @Test
    void aHandleAnEarlierCallOfTheBatchMintedIsUnknownToALaterCall() throws Exception {
        doAnswer(invocation -> {
            JsonNode arguments = invocation.getArgument(1);
            AiChatResourceRegistry resources = invocation.getArgument(2);
            if (arguments.has("handle")) {
                resources.resolve(arguments.get("handle").asString());
            }
            return null;
        }).when(toolExecutor).validateReferences(any(), any(), any());
        doAnswer(invocation -> {
            AiChatResourceRegistry resources = invocation.getArgument(2);
            return new AiAssistantToolResult(
                    Map.of("records", List.of(resources.register("deal", 41))), List.of());
        }).when(toolExecutor).execute(
                eq("search_records"), any(), any(), any(Boolean.class), any());
        answers(
                batch(
                        new AiToolCall("call_1", "search_records", search("Acme")),
                        new AiToolCall("call_2", "get_record", "{\"handle\":\"r1\"}")),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(toolExecutor).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true), any());
        verify(toolExecutor, never()).execute(
                eq("get_record"), any(), any(), any(Boolean.class), any());
        verify(persistenceService).proposeTool(TURN, 1, 2, "get_record", "{\"handle\":\"r1\"}");
        verify(persistenceService).failTool(TURN, 29, "{\"reason\":\"unknown_handle\"}");
        List<AiToolExchange> replayed = requests().getLast().exchanges();
        assertEquals(
                List.of(1, 2), replayed.stream().map(AiToolExchange::callOrdinal).toList());
        assertTrue(replayed.get(1).maskedResult().contains("unknown_handle"),
                "the guessed handle is answered with the refusal a lone call would get");
    }

    /**
     * A cache hit inside a batch that the replay cannot admit closes the turn, as a fresh read's
     * would.
     *
     * <p>The step's second call repeats a read the turn already made, so it is answered from the
     * cache; admitting that answer to the replay is refused as {@code tool_result_budget_exhausted},
     * which is closable. The batch is abandoned, its exchanges leave the replay whole, and the
     * turn goes to its closing step rather than failing.
     */
    @Test
    void aCacheHitTheReplayCannotAdmitInsideABatchGoesToTheClosingStep() throws Exception {
        doAnswer(invocation -> {
            AiAssistantPromptAssembler.ToolTurn prospective = invocation.getArgument(1);
            if (prospective.seq() == 2 && prospective.call() == 2) {
                throw new AiAssistantLoopException(
                        "tool_result_budget_exhausted", "tool_result_budget_exhausted");
            }
            return invocation.callRealMethod();
        }).when(promptAssembler).requireAdditionalNativeExchangeCapacity(
                any(), any(), any(), any(), any());
        answers(
                nativeTool("call_0", "search_records", search("alpha")),
                batch(
                        new AiToolCall("call_1", "search_records", search("beta")),
                        new AiToolCall("call_2", "search_records", search("alpha"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        AiNativeToolRequest closing = requests().getLast();
        assertTrue(closing.finalOnly(), "an abandoned batch must go to the closing step");
        assertEquals(List.of(1), closing.exchanges().stream().map(AiToolExchange::step).toList(),
                "no call of the abandoned step may be replayed");
    }

    /**
     * A plan published past the turn's allowance inside a batch refuses only that call.
     *
     * <p>Two four-plan batches spend the whole allowance. A third batch pairs one more plan with
     * a read: the plan settles as its own replayed {@code plan_updates_exhausted} refusal with a
     * failed row, and the read beside it still runs, rather than the whole turn failing.
     */
    @Test
    void aPlanPastTheAllowanceInsideABatchRefusesOnlyThatCall() throws Exception {
        answers(
                batch(
                        new AiToolCall("call_1", "set_todos", todos("one")),
                        new AiToolCall("call_2", "set_todos", todos("two")),
                        new AiToolCall("call_3", "set_todos", todos("three")),
                        new AiToolCall("call_4", "set_todos", todos("four"))),
                batch(
                        new AiToolCall("call_5", "set_todos", todos("five")),
                        new AiToolCall("call_6", "set_todos", todos("six")),
                        new AiToolCall("call_7", "set_todos", todos("seven")),
                        new AiToolCall("call_8", "set_todos", todos("eight"))),
                batch(
                        new AiToolCall("call_9", "set_todos", todos("nine")),
                        new AiToolCall("call_10", "search_records", search("alpha"))),
                finalAnswer());

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeTool(TURN, 3, 1, "set_todos", todos("nine"));
        verify(persistenceService).failTool(
                TURN, 29, "{\"reason\":\"plan_updates_exhausted\"}");
        verify(persistenceService).proposeTool(TURN, 3, 2, "search_records", search("alpha"));
        verify(toolExecutor).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true), any());
        List<AiToolExchange> stepThree = requests().getLast().exchanges().stream()
                .filter(exchange -> exchange.step() == 3)
                .toList();
        assertEquals(
                List.of(1, 2), stepThree.stream().map(AiToolExchange::callOrdinal).toList());
        assertTrue(stepThree.getFirst().maskedResult().contains("plan_updates_exhausted"));
    }

    /**
     * A repaired batch envelope is told it may batch again, up to the request's bound.
     *
     * <p>The retry request still invites a batch, so its repair must not tell the model to return
     * exactly one call and so steer it back to one read per step.
     */
    @Test
    void aBatchEnvelopeRepairOffersTheRequestsWholeBound() throws Exception {
        answers(
                new AiNativeToolCompletion.Malformed<>(
                        3, 5, "tool_calls", Optional.empty(), "native_duplicate_call_id"),
                finalAnswer());

        service.run(TURN);

        AiNativeToolRequest retry = requests().getLast();
        assertEquals(AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS, retry.maxParallelCalls());
        assertEquals(
                "Your previous native tool call violated the duplicate-call-id rule. Return up to "
                        + AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS
                        + " valid native tool calls or one valid JSON final answer.",
                retry.repairMessage());
    }

    private void useBudget(AiAssistantPromptBudget budget) {
        AiChatMessage userMessage = new AiChatMessage();
        userMessage.setId(TURN.userMessageId());
        userMessage.setAuthorKind("user");
        userMessage.setContent("Summarize my pipeline");
        when(memoryService.prepare(eq(TURN), any(), any(Instant.class), any())).thenReturn(
                new AiChatMemory(
                        List.of(userMessage),
                        budget,
                        0,
                        0,
                        true,
                        AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS));
    }

    private void routedReadSkill() {
        AiSkillCatalog.SkillSpec digest =
                new AiSkillCatalog().find("activity_digest_v1").orElseThrow();
        when(skillRouter.route(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(new AiSkillRouter.Routing(
                        digest, AiSkillRouter.MATCHED, null, false));
        when(skillPlanRunner.run(eq(TURN), any(), any(), any(), anyInt(), any()))
                .thenReturn(new AiSkillPlanRunner.Execution(
                        true,
                        Map.of("skill", digest.key(), "evidence", List.of()),
                        1,
                        false));
    }

    /**
     * Distinct evidence per distinct query, and the same evidence for a query ending in
     * {@code -again}, so a test can script a repeated result under different arguments.
     */
    private static AiAssistantToolResult resultFor(JsonNode arguments) {
        String query = arguments.path("query").asString();
        String evidence = query.endsWith("-again")
                ? query.substring(0, query.length() - "-again".length())
                : query;
        return new AiAssistantToolResult(Map.of("records", List.of(evidence)), List.of());
    }

    private static String todos(String item) {
        return "{\"items\":[\"" + item + "\"]}";
    }

    private static String search(String query) {
        return "{\"query\":\"" + query + "\",\"kinds\":[\"deal\"]}";
    }

    @SafeVarargs
    private void answers(AiNativeToolCompletion<AiAssistantStep.FinalAnswer>... responses) {
        OngoingStubbing<AiNativeToolCompletion<AiAssistantStep.FinalAnswer>> stubbing =
                when(invocationService.completeNativeToolsRepairable(
                        any(AiInvocation.class), eq(AiAssistantStep.FinalAnswer.class),
                        any(AiRawOutputGuard.class), any(AiRawOutputGuard.class),
                        any(AiResponseSchema.class), any(AiNativeToolRequest.class),
                        eq(directAdmission), any(Runnable.class)));
        for (AiNativeToolCompletion<AiAssistantStep.FinalAnswer> response : responses) {
            stubbing = stubbing.thenReturn(response);
        }
    }

    private List<AiNativeToolRequest> requests() {
        ArgumentCaptor<AiNativeToolRequest> requests =
                ArgumentCaptor.forClass(AiNativeToolRequest.class);
        verify(invocationService, atLeastOnce())
                .completeNativeToolsRepairable(
                        any(AiInvocation.class), eq(AiAssistantStep.FinalAnswer.class),
                        any(AiRawOutputGuard.class), any(AiRawOutputGuard.class),
                        any(AiResponseSchema.class), requests.capture(),
                        eq(directAdmission), any(Runnable.class));
        return List.copyOf(requests.getAllValues());
    }

    private AiNativeToolCompletion<AiAssistantStep.FinalAnswer> twoReads() throws Exception {
        return batch(
                new AiToolCall("call_1", "search_records", search("alpha")),
                new AiToolCall("call_2", "search_records", search("beta")));
    }

    private AiNativeToolCompletion<AiAssistantStep.FinalAnswer> duplicateBatch(
            String firstId, String secondId) throws Exception {
        return batch(
                new AiToolCall(firstId, "search_records", search("dup")),
                new AiToolCall(secondId, "search_records", search("dup")));
    }

    private AiNativeToolCompletion<AiAssistantStep.FinalAnswer> batch(AiToolCall... calls)
            throws Exception {
        List<JsonNode> arguments = new ArrayList<>(calls.length);
        for (AiToolCall call : calls) {
            arguments.add(objectMapper.readTree(call.arguments()));
        }
        return new AiNativeToolCompletion.Tool<>(
                List.of(calls),
                arguments,
                0,
                3,
                5,
                "tool_calls",
                Optional.empty(),
                Optional.empty());
    }

    private AiNativeToolCompletion<AiAssistantStep.FinalAnswer> nativeTool(
            String id, String name, String arguments) throws Exception {
        return new AiNativeToolCompletion.Tool<>(
                new AiToolCall(id, name, arguments),
                objectMapper.readTree(arguments),
                0,
                3,
                5,
                "tool_calls",
                Optional.empty());
    }

    private AiNativeToolCompletion<AiAssistantStep.FinalAnswer> load(String id, String toolset)
            throws Exception {
        return nativeTool(
                id, AiAssistantToolCatalog.FIND_TOOLS, "{\"toolset\":\"" + toolset + "\"}");
    }

    private static AiNativeToolCompletion<AiAssistantStep.FinalAnswer> finalAnswer() {
        return new AiNativeToolCompletion.Content<>(
                new AiStructuredRepairAttempt<>(
                        new AiStructuredOutcome.Parsed<>(
                                new AiAssistantStep.FinalAnswer("Nothing needs attention.", List.of()),
                                0, 3, 5, "stop"),
                        Optional.empty()),
                3,
                5,
                "stop",
                Optional.empty());
    }

    private static void assertRefusedStepReplayed(
            AiNativeToolRequest request, int calls, String reason) {
        List<AiToolExchange> refused = request.exchanges().stream()
                .filter(exchange -> exchange.callOrdinal() >= 1)
                .toList();
        assertEquals(calls, refused.size(), "every call of a refused batch is answered");
        for (int index = 0; index < calls; index++) {
            assertEquals(index + 1, refused.get(index).callOrdinal());
            assertTrue(refused.get(index).maskedResult().contains(reason),
                    "each refused call is answered with the stable reason");
        }
    }

    private static List<String> definitionNames(AiNativeToolRequest request) {
        return request.definitions().stream().map(definition -> definition.name()).toList();
    }

    private void verifyNoToolRowAndNoExecution() {
        verify(persistenceService, never()).proposeTool(any(), anyInt(), anyInt(), any(), any());
        verify(persistenceService, never()).proposeTool(
                any(), anyInt(), anyInt(), any(), any(), any());
        verify(persistenceService, never()).proposeWriteTool(any(), anyInt(), any());
        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class), any());
    }

    private void verifySecondCallNeverProposed() {
        verify(persistenceService).proposeTool(TURN, 1, 1, "search_records", search("alpha"));
        verify(persistenceService, never()).proposeTool(
                eq(TURN), eq(1), eq(2), anyString(), anyString());
        verify(persistenceService, never()).proposeTool(
                eq(TURN), eq(1), eq(2), anyString(), anyString(), anyString());
    }
}
