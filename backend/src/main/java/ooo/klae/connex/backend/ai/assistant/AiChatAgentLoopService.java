package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.DirectAdmissionRejectedException;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Rejection;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiNativeToolCompletion;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredRepair;
import ooo.klae.connex.backend.ai.AiStructuredRepairAttempt;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ExecutedReplay;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolBudgetAudit;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolTurn;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseHeartbeat;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.provider.AiImageInputUnsupportedException;
import ooo.klae.connex.backend.ai.provider.AiInvocationProtocol;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;
import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.AiBudgetExhaustedException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes the bounded masked assistant loop and commits only authorized durable outcomes. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatAgentLoopService {
    static final int HARD_MAX_STEPS = 64;

    /**
     * The most {@code ai_chat_tool_call} rows one turn can leave behind.
     *
     * <p>A projection bound, not a guard. The step loop already refuses past {@link #HARD_MAX_STEPS}
     * and a step carries at most {@link AiProviderCapabilities#MAX_PARALLEL_TOOL_CALLS} calls, so a
     * check against this number could never fire; what it is for is the row limit the progress
     * projection passes to {@code listToolCallsByTurn}. That limit used to be the step ceiling
     * itself, which was exact only while one step wrote one row. A turn whose steps may write
     * several rows would silently lose real milestones under the old limit while every suffixed key
     * it wrote still parsed and looked healthy.
     */
    static final int MAX_TOOL_CALL_ROWS_PER_TURN =
            HARD_MAX_STEPS * AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS;

    private static final int MAX_CONSECUTIVE_NO_PROGRESS_STEPS = 2;
    /**
     * The whole-turn narration budget. Each segment is already bounded to a status sentence; this
     * bounds their sum so a long agentic turn cannot grow the durable answer metadata without
     * limit, which no database constraint would catch on the JSON column.
     */
    private static final int MAX_TURN_NARRATION_CHARS = 8_000;
    /**
     * How many times one turn may publish its plan. A plan that keeps being rewritten is a model
     * talking to itself rather than working, and the refusal is recoverable, so the model is told
     * to get on with it rather than having its turn ended.
     */
    private static final int MAX_TURN_PLAN_PUBLICATIONS = 8;
    private static final String INTERNAL_ERROR = "internal_error";
    private static final String TOOL_OUTSIDE_SKILL_AUTHORITY = "tool_outside_skill_authority";
    /**
     * The terminal reasons that mean the turn ran out of room to investigate rather than losing the
     * authority, the capacity, or the provider it needed.
     *
     * <p>Each of these settles with tool evidence already gathered and a requester who asked a
     * question nobody answered, so the loop spends one closing step turning that evidence into an
     * answer instead of reporting the guard it met. A reason outside this set either withdrew the
     * requester's authority, exhausted the budget the closing step would itself have to spend, or
     * left the provider unable to answer at all.
     *
     * <p>A demask warning is deliberately absent even though it settles as {@code malformed_output}:
     * it means the step referenced a placeholder this turn never issued, and re-prompting a model
     * that is already inventing identifiers is not a route back to a trustworthy answer.
     */
    private static final Set<String> CLOSABLE_REASONS = Set.of(
            "agent_backstop_exceeded",
            "malformed_output",
            "no_progress",
            "schema_repair_failed",
            "skill_budget_exceeded",
            "step_cap_exceeded",
            "tool_result_budget_exhausted");
    /**
     * The server-authored instruction that turns the closing step into an answer.
     *
     * <p>Travels outside the CRM_DATA delimiters as every other directive does, and states the
     * honesty requirement explicitly: a bounded answer that names what went unchecked is useful,
     * and one that hides the gap is worse than the failure it replaced.
     */
    private static final String CLOSING_DIRECTIVE = """
            You have no investigation steps left. Answer the question now, using only the evidence \
            already gathered in this turn. Do not request another tool. Cite the records you did \
            read, and state plainly in the answer which parts of the question you could not check \
            and why. If the evidence supports no answer at all, say exactly that.""";
    /** The outcome of a tool call that settled and left the turn free to take its next step. */
    private static final StepCallOutcome CONTINUE = new StepCallOutcome.Continue();
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final int MAX_GENERATED_TITLE_CHARS = 80;
    private static final double TEMPERATURE = 0.1;

    private final AiInvocationService invocationService;
    private final AiInvocationAdmissionService invocationAdmissionService;
    private final AiProperties aiProperties;
    private final AiAssistantStepGuard stepGuard;
    private final AiAssistantToolCatalog toolCatalog;
    private final AiAssistantStepSchema stepSchema;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantToolsetLoader toolsetLoader;
    private final AiAssistantWriteToolService writeToolService;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiSkillRouter skillRouter;
    private final AiSkillPlanRunner skillPlanRunner;
    private final AiChatMemoryService memoryService;
    private final AiChatAttachmentContextService attachmentContextService;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiRunLeaseHeartbeat runLeaseHeartbeat;
    private final AiRunLeaseService runLeaseService;
    private final AiChatProgressService progressService;
    private final AiChatCitationProjector citationProjector;
    private final AiRestrictionEpoch restrictionEpoch;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final AiWorkspaceGovernanceService governanceService;
    private final Clock clock;

    /**
     * Runs one committed turn under the shared generation context.
     *
     * <p>The claim that flips the turn to running also takes its run lease, and this method keeps
     * that lease alive for as long as it works. It never releases it: the durable terminal write
     * tombstones the lease, so a process killed between here and that write leaves an expiring
     * lease another instance can settle rather than a running turn with no owner. The {@code
     * finally} therefore only stops the heartbeat and drops this instance's local token, so a turn
     * whose terminal write never lands cannot leave that token behind for the life of the process.
     *
     * <p>Ownership is polled between every pair of turn steps that can block, not only inside the
     * step loop: preparing the memory and the attachment context each invoke the model on their
     * own, and a routed skill plan runs its own durable reads, all before the first model step.
     * Polling only in the step loop would leave the stated reaction bound — one heartbeat interval
     * plus the in-flight provider call's remaining deadline — true of the loop and false of the
     * preparation, where a stopped owner could still charge the organization for further provider
     * calls.
     *
     * <p>Those preparation phases are multi-step in their own right — memory compaction summarizes
     * one window of history per round, the attachment context describes one image per file, and a
     * routed plan runs one declared step at a time — so the poll is threaded into the per-substep
     * guard each of them already runs rather than bolted on at their boundaries. The honest bound
     * this buys is therefore the same one the step loop states: a stopped owner stops at the next
     * substep, which is at most one heartbeat interval after the stop plus whatever remains of the
     * provider call already in flight.
     */
    public AiGenerationTaskResult<AiChatTurnGenerationResult> run(AiChatQueuedTurn turn) {
        AiRunLeaseGuard ownership = new AiRunLeaseGuard(aiProperties.getRunLeaseTtl());
        AutoCloseable heartbeat = null;
        AiRunLease lease = null;
        try {
            requireWorkspaceEnabled(turn);
            try {
                lease = persistenceService.markRunning(turn, ownership);
            } catch (ForbiddenException exception) {
                return AiGenerationTaskResult.failed("access_revoked");
            }
            heartbeat = runLeaseHeartbeat.start(lease, ownership);
            Instant deadline = clock.instant().plus(AiAssistantTurnBudget.TURN);
            publish(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    0, "state", null, "running", null));
            MaskingContext maskingContext = new MaskingContext(turn.privacyMode());
            AiChatResourceRegistry resources = new AiChatResourceRegistry(maskingContext);
            AiChatStreamingProgress streamingProgress = turn.streamed()
                    ? new AiChatStreamingProgress(turn, persistenceService, maskingContext)
                    : null;
            Runnable ownershipGuard = () -> requireOwnership(ownership);
            AiChatMemory memory = memoryService.prepare(
                    turn, maskingContext, deadline, ownershipGuard);
            List<AiChatMessage> history = memory.history();
            AiChatMessage initiatingMessage = history.stream()
                    .filter(message -> message.getId() == turn.userMessageId())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Assistant initiating message is unavailable"));
            List<AiChatPageContextDto> promptContext =
                    new ArrayList<>(turn.pageContext());
            promptContext.addAll(promptAssembler.replayPageContext(history));
            AiAssistantToolResult pageContext = toolExecutor.pageContext(
                    promptContext, resources);
            pageContext.identifiers().forEach(identifier -> identifier.seed(maskingContext));
            if (ownership.isStopped()) {
                return AiGenerationTaskResult.failed(AiAssistantTerminalReasons.OWNER_LOST);
            }
            AiChatAttachmentContext attachmentContext = attachmentContextService.prepare(
                    turn, deadline, maskingContext, ownershipGuard);
            TurnToolState state = new TurnToolState();
            ToolExecutionContext toolContext = new ToolExecutionContext(
                    turn,
                    ownership,
                    deadline,
                    maskingContext,
                    resources,
                    memory.budget(),
                    attachmentContext.data());
            boolean nativeTools = memory.nativeTools();
            AiStructuredRepair repair = null;
            Integer nativeToolsDegradedStatus = null;
            int nativeProviderAttempts = 0;
            List<AiChatNarration> narration = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger narrationBytes =
                    new java.util.concurrent.atomic.AtomicInteger();
            int inputTokens = addTokens(memory.inputTokens(), attachmentContext.inputTokens());
            int outputTokens = addTokens(memory.outputTokens(), attachmentContext.outputTokens());
            AiSkillRouter.Routing routing = skillRouter.route(
                    turn.workspaceId(),
                    turn.userId(),
                    initiatingMessage.getContent(),
                    promptContext,
                    turn.scope());
            // Carried on every turn that declares a scope, routed or not: the generic loop is
            // exactly where a model would otherwise reach for a read the declaration cannot be
            // applied to and have the turn refused for it.
            String scopeDirective = AiChatScopedToolPolicy.directive(turn.scope());
            AiAssistantPromptAssembler.SkillContext skillContext =
                    AiAssistantPromptAssembler.SkillContext.NONE
                            .withScopeDirective(scopeDirective);
            AiAssistantPromptAssembler.SkillReference skillReference = null;
            int stepOffset = 0;
            int maxSteps = Math.min(
                    governanceService.assistantMaxSteps(turn.workspaceId()), HARD_MAX_STEPS);
            if (routing.routed()) {
                if (ownership.isStopped()) {
                    return AiGenerationTaskResult.failed(AiAssistantTerminalReasons.OWNER_LOST);
                }
                AiSkillPlanRunner.Execution execution = skillPlanRunner.run(
                        turn,
                        routing,
                        turn.scope(),
                        resources,
                        memory.budget().toolResultBytes(),
                        () -> {
                            requireOwnership(ownership);
                            requireCurrentToolExecution(turn);
                        });
                // Every step the plan consumed already owns a durable idempotency key, so the
                // model loop resumes after them even when the plan produced nothing usable.
                stepOffset = execution.lastStepNumber();
                maxSteps = Math.min(maxSteps, HARD_MAX_STEPS - stepOffset);
                if (execution.executed()) {
                    Set<Toolset> seeded = seededToolsets(routing.skill());
                    // Attribution is written only once the plan actually produced the evidence the
                    // answer is built from, so the durable turn row and the answer's own skill
                    // metadata can never name a declaration the turn did not really run under.
                    persistenceService.applySkill(
                            turn, routing.skill().key(), routing.skill().version());
                    state.loadedToolsets.addAll(seeded);
                    skillReference = new AiAssistantPromptAssembler.SkillReference(
                            routing.skill().key(), routing.skill().version());
                    skillContext = new AiAssistantPromptAssembler.SkillContext(
                            routing.skill().directive(), execution.evidence(), scopeDirective);
                    // The server-owned plan already retrieved the evidence, so the model gets the
                    // skill's small synthesis budget instead of the improvisation budget.
                    maxSteps = Math.min(maxSteps, routing.skill().budgets().maxModelSteps());
                }
            }

            AiSkillCatalog.SkillSpec activeSkill = skillReference == null ? null : routing.skill();
            int consumedSteps = 0;
            int stepCursor = 0;
            boolean closingAttempted = false;
            boolean closingPending = false;
            String closingReason = null;
            steps:
            while (true) {
                boolean closing = closingPending;
                closingPending = false;
                if (consumedSteps >= maxSteps || stepOffset + stepCursor >= HARD_MAX_STEPS) {
                    return AiGenerationTaskResult.failed(closing
                            ? closingReason
                            : exhaustionReason(
                                    maxSteps, stepOffset + stepCursor, skillReference != null));
                }
                if (!closingAttempted
                        && lastPermittedStep(
                                consumedSteps, maxSteps, stepOffset + stepCursor + 1)) {
                    closing = true;
                    closingAttempted = true;
                    closingReason = exhaustionReason(
                            maxSteps, stepOffset + stepCursor + 1, skillReference != null);
                }
                stepCursor++;
                int stepNumber = stepOffset + stepCursor;
                AiAssistantPromptAssembler.SkillContext stepContext = closing
                        ? skillContext.withClosingDirective(CLOSING_DIRECTIVE)
                        : skillContext;
                requireWorkspaceEnabled(turn);
                AiAssistantPromptBudget.requireAssistantContextFloor(
                        invocationService.currentProviderCapabilities(AiFeature.ASSISTANT_CHAT)
                                .contextWindowTokens());
                if (ownership.isStopped()) {
                    return AiGenerationTaskResult.failed(
                            AiAssistantTerminalReasons.OWNER_LOST);
                }
                if (deadlineReached(deadline)) {
                    return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                }
                AiStructuredRepair stepRepair = repair;
                boolean nativeMalformedRetried = false;
                AiStructuredRepairAttempt<AiAssistantStep> attempt = null;
                AiStructuredOutcome<AiAssistantStep> outcome = null;
                Optional<AiToolCall> nativeProviderCall = Optional.empty();
                AiChatStreamingProgress.Observer streamingObserver = null;
                while (outcome == null) {
                    List<AiToolDefinition> nativeDefinitions = nativeTools
                            ? promptAssembler.nativeToolDefinitions(state.loadedToolsets)
                            : List.of();
                    MaskedPrompt prompt = nativeTools
                            ? promptAssembler.assembleNative(
                                    history,
                                    pageContext,
                                    state.toolTurns,
                                    maskingContext,
                                    resources,
                                    attachmentContext.data(),
                                    memory.budget(),
                                    stepContext,
                                    state.loadedToolsets)
                            : promptAssembler.assemble(
                                    history,
                                    pageContext,
                                    state.toolTurns,
                                    maskingContext,
                                    resources,
                                    attachmentContext.data(),
                                    memory.budget(),
                                    stepRepair,
                                    stepContext,
                                    state.loadedToolsets);
                    AiAssistantPromptAssembler.NativeReplay nativeReplay = nativeTools
                            ? promptAssembler.nativeReplay(
                                    state.toolTurns,
                                    state.nativeCalls,
                                    maskingContext,
                                    memory.budget(),
                                    stepRepair)
                            : new AiAssistantPromptAssembler.NativeReplay(
                                    List.of(), null, ToolBudgetAudit.NONE);
                    if (nativeTools) {
                        state.toolBudgetAudit = nativeReplay.audit();
                    }
                    AiInvocation invocation = new AiInvocation(
                            AiFeature.ASSISTANT_CHAT,
                            maskingContext,
                            prompt,
                            List.of(),
                            memory.budget().maxOutputTokens(),
                            TEMPERATURE,
                            aiProperties.isAssistantThinkingEnabled(),
                            deadline,
                            nativeTools
                                    ? AiInvocationProtocol.NATIVE_TOOLS
                                    : AiInvocationProtocol.JSON_REACT,
                            nativeToolsDegradedStatus,
                            memory.budget().outputTokensClamped());
                    if (streamingProgress != null) {
                        streamingObserver = streamingProgress.observer(nativeTools);
                        invocation = invocation.withStreamObserver(streamingObserver);
                    }
                    AiRawOutputGuard outputGuard = stepGuard.forStep(
                            state.loadedToolsets,
                            maskingContext.tokenBindings().stream()
                                    .map(Map.Entry::getKey)
                                    .collect(Collectors.toUnmodifiableSet()));
                    boolean degradationEligible = nativeTools
                            && nativeProviderAttempts == 0
                            && state.toolTurns.isEmpty()
                            && state.nativeCalls.isEmpty();
                    boolean nativeMalformed = false;
                    Optional<String> stepNarration = Optional.empty();
                    try (AiInvocationAdmissionService.DirectAdmission admission =
                            invocationAdmissionService.acquireDirect()) {
                        Runnable providerGuard = () -> {
                            requireWorkspaceEnabled(turn);
                            persistenceService.requireRunning(turn);
                        };
                        if (nativeTools) {
                            AiNativeToolRequest nativeRequest = new AiNativeToolRequest(
                                    nativeDefinitions,
                                    nativeReplay.exchanges(),
                                    nativeReplay.repairMessage(),
                                    closing,
                                    memory.parallelToolCalls());
                            nativeProviderAttempts++;
                            NativeStepAttempt nativeAttempt = nativeStepAttempt(
                                    invocationService.completeNativeToolsRepairable(
                                            invocation,
                                            AiAssistantStep.FinalAnswer.class,
                                            outputGuard,
                                            stepGuard.finalAnswerForIssuedPlaceholders(
                                                    maskingContext.tokenBindings().stream()
                                                            .map(Map.Entry::getKey)
                                                            .collect(Collectors.toUnmodifiableSet())),
                                            stepSchema.finalResponseSchema(),
                                            nativeRequest,
                                            admission,
                                            providerGuard));
                            attempt = nativeAttempt.attempt();
                            nativeProviderCall = nativeAttempt.providerCall();
                            nativeMalformed = nativeAttempt.malformed();
                            stepNarration = nativeAttempt.narration();
                        } else {
                            attempt = invocationService.completeStructuredRepairable(
                                    invocation,
                                    AiAssistantStep.class,
                                    outputGuard,
                                    closing
                                            ? stepSchema.closingResponseSchema()
                                            : stepSchema.responseSchema(state.loadedToolsets),
                                    admission,
                                    providerGuard);
                        }
                    } catch (AiProviderRequestRejectedException exception) {
                        if (!degradationEligible || !exception.isClientError()) {
                            throw exception;
                        }
                        nativeTools = false;
                        nativeToolsDegradedStatus = exception.statusCode();
                        state.nativeCalls.clear();
                        state.toolTurns.clear();
                        stepRepair = null;
                        repair = null;
                        continue;
                    }
                    outcome = java.util.Objects.requireNonNull(attempt, "attempt").outcome();
                    requireWorkspaceEnabled(turn);
                    persistenceService.requireRunning(turn);
                    requireCurrentAccess(turn);
                    publishThinking(turn, stepNumber, attempt.reasoning());
                    stepNarration
                            .map(AiChatRecordLinkRewriter::stripDurableLinks)
                            .filter(text -> !text.isBlank())
                            .filter(text -> narrationBytes.get() + text.length()
                                    <= MAX_TURN_NARRATION_CHARS)
                            .ifPresent(text -> {
                                narrationBytes.addAndGet(text.length());
                                narration.add(new AiChatNarration(stepNumber, text));
                                // Requester-only, exactly like thinking: narration names records
                                // this member's own tool results reached, and a shared viewer whose
                                // access is narrower must not learn a label live that the settled
                                // transcript would withhold from them.
                                publish(turn.userId(), new AiChatStepFrameDto(
                                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                        stepNumber, "narration", null, null, null, null, text));
                            });
                    inputTokens = addTokens(inputTokens, inputTokens(outcome));
                    outputTokens = addTokens(outputTokens, outputTokens(outcome));
                    if (ownership.isStopped()) {
                        return AiGenerationTaskResult.failed(
                                AiAssistantTerminalReasons.OWNER_LOST);
                    }
                    if (deadlineReached(deadline)) {
                        return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                    }
                    if (nativeMalformed) {
                        resetMalformedStream(streamingProgress, streamingObserver);
                        if (nativeMalformedRetried) {
                            if (closingAttempted) {
                                return AiGenerationTaskResult.failed("malformed_output");
                            }
                            closingAttempted = true;
                            closingPending = true;
                            closingReason = "malformed_output";
                            continue steps;
                        }
                        nativeMalformedRetried = true;
                        stepRepair = attempt.repair().orElseThrow();
                        outcome = null;
                    }
                }
                if (outcome instanceof AiStructuredOutcome.Malformed<?>) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    if (repair != null || attempt.repair().isEmpty()) {
                        if (closingAttempted) {
                            return AiGenerationTaskResult.failed("schema_repair_failed");
                        }
                        closingAttempted = true;
                        closingPending = true;
                        closingReason = "schema_repair_failed";
                        continue steps;
                    }
                    // A repair iteration produced no model decision, so it is not charged to the
                    // step budget. It is still bounded: a second consecutive malformed step ends
                    // the turn above, and the backstop bounds the step numbers regardless. A
                    // closing step stays closing across its repair — otherwise the retry would
                    // run with the ordinary schema and a tool it returned would execute past the
                    // closing boundary.
                    repair = attempt.repair().orElseThrow();
                    closingPending = closing;
                    continue;
                }
                if (!(outcome instanceof AiStructuredOutcome.Parsed<?> parsed)
                        || !(parsed.value() instanceof AiAssistantStep step)) {
                    if (closingAttempted) {
                        return AiGenerationTaskResult.failed("malformed_output");
                    }
                    resetMalformedStream(streamingProgress, streamingObserver);
                    closingAttempted = true;
                    closingPending = true;
                    closingReason = "malformed_output";
                    continue steps;
                }
                if (parsed.demaskWarnings() != 0) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                repair = null;
                consumedSteps++;
                if (step.tool() != null) {
                    AiAssistantStepCalls stepCalls =
                            AiAssistantStepCalls.of(step.tool(), nativeProviderCall);
                    for (AiAssistantStepCalls.Call stepCall : stepCalls.calls()) {
                        requireSkillAuthority(activeSkill, stepCall.tool().name());
                    }
                    if (closing) {
                        return AiGenerationTaskResult.failed(closingReason);
                    }
                    if (streamingObserver != null) {
                        streamingObserver.requireNoTerminalText();
                    }
                    StepCallOutcome callOutcome = CONTINUE;
                    for (AiAssistantStepCalls.Call stepCall : stepCalls.calls()) {
                        callOutcome = executeStepCall(
                                toolContext, stepNumber, closingAttempted, nativeTools,
                                stepCall, state);
                        if (!(callOutcome instanceof StepCallOutcome.Continue)) {
                            break;
                        }
                    }
                    switch (callOutcome) {
                        case StepCallOutcome.Continue settled -> { }
                        case StepCallOutcome.Close close -> {
                            closingAttempted = true;
                            closingPending = true;
                            closingReason = close.reason();
                        }
                        case StepCallOutcome.Fail fail -> {
                            return AiGenerationTaskResult.failed(fail.reason());
                        }
                        case StepCallOutcome.TimedOut timedOut -> {
                            return AiGenerationTaskResult.timedOut(timedOut.reason());
                        }
                    }
                    continue;
                }

                AiAssistantStep.FinalAnswer finalAnswer = step.finalAnswer();
                if (finalAnswer == null || finalAnswer.text() == null
                        || finalAnswer.text().isBlank()
                        || finalAnswer.text().length() > MAX_FINAL_CHARS) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    if (closingAttempted) {
                        return AiGenerationTaskResult.failed("malformed_output");
                    }
                    closingAttempted = true;
                    closingPending = true;
                    closingReason = "malformed_output";
                    continue steps;
                }
                String persistedText;
                try {
                    persistedText = streamingObserver == null
                            ? screenedFinalText(finalAnswer.text())
                            : streamingObserver.finish(finalAnswer.text());
                } catch (AiAssistantLoopException exception) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    throw exception;
                }
                boolean omitted = MaskingEngine.OMITTED_BY_POLICY.equals(persistedText);
                List<String> citations = omitted ? List.of() : finalAnswer.citations();
                try {
                    resources.requireKnownCitations(citations);
                } catch (AiAssistantLoopException exception) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    throw exception;
                }
                List<String> suggestions = omitted
                        ? List.of()
                        : AiAssistantStepGuard.filterSuggestions(finalAnswer.suggestions());
                Map<String, AiChatResourceRegistry.ResourceRef> citedResources =
                        resources.snapshot();
                if (!omitted) {
                    persistedText = AiChatRecordLinkRewriter.rewrite(
                            persistedText, citedResources, Set.copyOf(citations));
                }
                String metadata = promptAssembler.finalMetadata(
                        turn.turnId(), citations, suggestions, citedResources,
                        citationProjector.observe(
                                turn.workspaceId(), citations, citedResources),
                        state.toolBudgetAudit,
                        skillReference,
                        omitted ? List.of() : List.copyOf(narration),
                        omitted ? List.of() : List.copyOf(state.todos));
                requireCurrentAccess(turn);
                persistenceService.resolve(
                        turn, persistedText, metadata, inputTokens, outputTokens);
                if (!omitted) {
                    applyGeneratedTitle(turn, finalAnswer.title());
                }
                return AiGenerationTaskResult.resolved(
                        new AiChatTurnGenerationResult(turn.turnId(), "resolved"));
            }
        } catch (AiAssistantLoopException exception) {
            if ("turn_deadline_exceeded".equals(exception.terminalReason())) {
                return AiGenerationTaskResult.timedOut(exception.terminalReason());
            }
            return AiGenerationTaskResult.failed(exception.terminalReason());
        } catch (AiBudgetExhaustedException exception) {
            return AiGenerationTaskResult.failed("budget_exhausted");
        } catch (DirectAdmissionRejectedException exception) {
            return AiGenerationTaskResult.failed(
                    exception.rejection() == Rejection.ORGANIZATION_QUOTA
                            ? "org_invocation_quota_exhausted"
                            : "invocation_capacity_exhausted");
        } catch (TooManyRequestsException exception) {
            return AiGenerationTaskResult.failed("quota_exhausted");
        } catch (AiImageInputUnsupportedException exception) {
            return AiGenerationTaskResult.failed("image_input_unsupported");
        } catch (AiProviderCallerDeadlineExceededException exception) {
            return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
        } catch (AiProviderIdleTimeoutException exception) {
            return AiGenerationTaskResult.timedOut("provider_idle_timeout");
        } catch (AiProviderException exception) {
            return AiGenerationTaskResult.failed("provider_error");
        } catch (ResourceNotFoundException exception) {
            return AiGenerationTaskResult.failed("access_revoked");
        } catch (ForbiddenException exception) {
            if (!governanceService.isEnabled(turn.workspaceId())) {
                return AiGenerationTaskResult.failed("workspace_disabled");
            }
            if (restrictionsChanged(turn)) {
                return AiGenerationTaskResult.failed("restrictions_changed");
            }
            return AiGenerationTaskResult.failed("access_revoked");
        } catch (RuntimeException exception) {
            if (restrictionsChanged(turn)) {
                return AiGenerationTaskResult.failed("restrictions_changed");
            }
            if (Thread.currentThread().isInterrupted()) {
                return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
            }
            log.warn("Assistant turn failed exceptionClass={}",
                    exception.getClass().getName());
            return AiGenerationTaskResult.failed(INTERNAL_ERROR);
        } finally {
            stopHeartbeat(heartbeat);
            runLeaseService.forgetLocalToken(lease);
        }
    }

    /**
     * Runs one tool call of a model step and names the loop's next move.
     *
     * <p>Both protocols converge here: the JSON ReAct step object and a native response each hand
     * the step loop an {@link AiAssistantStepCalls} and every element of it walks this one path, so
     * a refusal, a budget admission, a durable row and a published milestone are written in exactly
     * one place rather than once per protocol.
     *
     * <p>The ownership and deadline polls live here rather than beside the step's other checks.
     * They bound how long a turn whose lease was reclaimed or whose wall clock expired may keep
     * dispatching tools, and that bound has to hold per call rather than per model decision. Both
     * return their outcome directly instead of throwing, so neither writes a durable row for a turn
     * the loop no longer owns.
     *
     * @param context the per-turn surfaces every tool call of the turn executes against
     * @param stepNumber the durable number of the model step this call belongs to
     * @param closingAttempted whether the turn already spent its closing step
     * @param nativeTools whether this step ran on the native tool protocol
     * @param call the proposed tool and the provider call that carried it
     * @param state the turn's tool state, which this call advances
     * @return what the step loop must do next
     */
    private StepCallOutcome executeStepCall(
            ToolExecutionContext context,
            int stepNumber,
            boolean closingAttempted,
            boolean nativeTools,
            AiAssistantStepCalls.Call call,
            TurnToolState state) {
        AiChatQueuedTurn turn = context.turn();
        if (context.ownership().isStopped()) {
            return new StepCallOutcome.Fail(AiAssistantTerminalReasons.OWNER_LOST);
        }
        if (deadlineReached(context.deadline())) {
            return new StepCallOutcome.TimedOut("turn_deadline_exceeded");
        }
        requireCurrentAccess(turn);
        AiAssistantToolCallRef callRef = new AiAssistantToolCallRef(stepNumber, call.ordinal());
        String toolName = call.tool().name();
        String argumentsJson = serialize(call.tool().args());
        String toolCallKey = toolName + "\n" + serialize(canonicalize(call.tool().args()));
        String thoughtSignature = call.thoughtSignature();
        try {
            requireToolsetLoaded(state.loadedToolsets, toolName);
            recordNativeCall(nativeTools, state.nativeCalls, callRef, call.providerCall());
            toolExecutor.validateReferences(toolName, call.tool().args(), context.resources());
        } catch (AiAssistantLoopException exception) {
            if (!exception.recoverable()) {
                throw exception;
            }
            boolean replayable = !nativeTools || state.nativeCalls.containsKey(callRef);
            int refusedCallId = thoughtSignature == null
                    ? persistenceService.proposeTool(
                            turn, stepNumber, call.ordinal(), toolName, argumentsJson)
                    : persistenceService.proposeTool(
                            turn, stepNumber, call.ordinal(), toolName, argumentsJson,
                            thoughtSignature);
            failTool(turn, refusedCallId, exception.detailReason());
            publishToolStep(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    stepNumber, "step", toolName,
                    "failed", exception.detailReason()));
            state.noProgressSteps++;
            if (replayable) {
                ToolTurn refusedTurn = new ToolTurn(
                        stepNumber, call.ordinal(), toolName,
                        refusedToolResult(exception.detailReason()));
                try {
                    state.toolBudgetAudit = requireAdditionalToolCapacity(
                            nativeTools, state.toolTurns, refusedTurn, state.nativeCalls,
                            context.maskingContext(), context.budget());
                    state.toolTurns.add(refusedTurn);
                } catch (AiAssistantLoopException capacity) {
                    if (!closingAttempted
                            && CLOSABLE_REASONS.contains(capacity.terminalReason())) {
                        return new StepCallOutcome.Close(capacity.terminalReason());
                    }
                    throw capacity;
                }
            }
            if (state.noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                return noProgressOutcome(closingAttempted);
            }
            return CONTINUE;
        }
        boolean findTools = AiAssistantToolCatalog.FIND_TOOLS.equals(toolName);
        AiAssistantToolResult cachedResult = findTools
                ? null
                : state.toolResultCache.get(toolCallKey);
        if (cachedResult != null) {
            state.noProgressSteps++;
            if (state.noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                return noProgressOutcome(closingAttempted);
            }
            ToolTurn cachedTurn = new ToolTurn(
                    stepNumber, call.ordinal(), toolName, cachedResult);
            state.toolBudgetAudit = requireAdditionalToolCapacity(
                    nativeTools,
                    state.toolTurns,
                    cachedTurn,
                    state.nativeCalls,
                    context.maskingContext(),
                    context.budget());
            state.toolTurns.add(cachedTurn);
            return CONTINUE;
        }
        if ("set_todos".equals(toolName)
                && state.planPublications >= MAX_TURN_PLAN_PUBLICATIONS) {
            throw AiAssistantLoopException.refusedArguments("plan_updates_exhausted");
        }
        if ("set_todos".equals(toolName)) {
            state.planPublications++;
        }
        if (toolCatalog.isWrite(toolName)) {
            AiAssistantPreparedWrite write = writeToolService.prepare(
                    toolName, call.tool().args(), context.resources(), turn.restrictionEpoch());
            if (!context.attachments().isEmpty()
                    && write.tier() == AiAssistantToolCatalog.ToolTier.AUTO) {
                return new StepCallOutcome.Fail("attachment_auto_write_blocked");
            }
            AiAssistantToolProposal proposal = thoughtSignature == null
                    ? persistenceService.proposeWriteTool(turn, stepNumber, write)
                    : persistenceService.proposeWriteTool(
                            turn, stepNumber, write, thoughtSignature);
            int toolCallId = proposal.id();
            publish(turn.userId(), new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    stepNumber, "step", toolName,
                    "proposed", null, toolCallId));
            try {
                requireCurrentToolExecution(turn);
                AiAssistantToolResult toolResult;
                boolean replayed = false;
                ToolBudgetAudit admittedToolBudgetAudit;
                if (write.tier() == AiAssistantToolCatalog.ToolTier.AUTO) {
                    AiAssistantWriteToolService.WriteExecution execution =
                            writeToolService.executeAuto(
                                    turn,
                                    toolCallId,
                                    candidate -> requireAdditionalToolCapacity(
                                            nativeTools,
                                            state.toolTurns,
                                            new ToolTurn(
                                                    stepNumber, call.ordinal(),
                                                    toolName, candidate),
                                            state.nativeCalls,
                                            context.maskingContext(),
                                            context.budget()));
                    toolResult = execution.toolResult();
                    replayed = execution.replayed();
                    if (replayed) {
                        ExecutedReplay executedReplay = nativeTools
                                ? promptAssembler.withExecutedNativeReplay(
                                        state.toolTurns,
                                        new ToolTurn(
                                                stepNumber, call.ordinal(), toolName,
                                                toolResult),
                                        state.nativeCalls,
                                        context.maskingContext(),
                                        context.budget())
                                : promptAssembler.withExecutedReplay(
                                        state.toolTurns,
                                        new ToolTurn(
                                                stepNumber, call.ordinal(), toolName,
                                                toolResult),
                                        context.maskingContext(),
                                        context.budget());
                        state.toolTurns.clear();
                        state.toolTurns.addAll(executedReplay.toolTurns());
                        toolResult = state.toolTurns.getLast().result();
                        admittedToolBudgetAudit = executedReplay.audit();
                    } else {
                        admittedToolBudgetAudit = requireAdditionalToolCapacity(
                                nativeTools,
                                state.toolTurns,
                                new ToolTurn(
                                        stepNumber, call.ordinal(), toolName, toolResult),
                                state.nativeCalls,
                                context.maskingContext(),
                                context.budget());
                    }
                } else {
                    toolResult = writeToolService.proposalResult(write, proposal);
                    admittedToolBudgetAudit = requireAdditionalToolCapacity(
                            nativeTools,
                            state.toolTurns,
                            new ToolTurn(
                                    stepNumber, call.ordinal(), toolName, toolResult),
                            state.nativeCalls,
                            context.maskingContext(),
                            context.budget());
                }
                state.toolBudgetAudit = admittedToolBudgetAudit;
                String status = write.tier() == AiAssistantToolCatalog.ToolTier.AUTO
                        ? "executed"
                        : ("executed".equals(proposal.status())
                                ? "executed"
                                : "approval_required");
                publish(turn.userId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "step", toolName,
                        status, null, toolCallId));
                String resultJson = promptAssembler.durableToolResult(toolResult);
                state.toolResultCache.put(toolCallKey, toolResult);
                if (state.seenToolResults.add(resultJson)) {
                    state.noProgressSteps = 0;
                } else {
                    state.noProgressSteps++;
                }
                if (!replayed) {
                    state.toolTurns.add(new ToolTurn(
                            stepNumber, call.ordinal(), toolName, toolResult));
                }
                if (state.noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                    return noProgressOutcome(closingAttempted);
                }
            } catch (AiAssistantLoopException exception) {
                failTool(turn, toolCallId, exception.detailReason());
                publish(turn.userId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "step", toolName,
                        "failed", exception.detailReason(), toolCallId));
                if (!closingAttempted
                        && CLOSABLE_REASONS.contains(exception.terminalReason())) {
                    return new StepCallOutcome.Close(exception.terminalReason());
                }
                return new StepCallOutcome.Fail(exception.terminalReason());
            } catch (RuntimeException exception) {
                String reason = toolFailureReason(exception);
                failTool(turn, toolCallId, reason);
                publish(turn.userId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "step", toolName,
                        "failed", reason, toolCallId));
                return new StepCallOutcome.Fail(reason);
            }
            return CONTINUE;
        }
        int toolCallId = thoughtSignature == null
                ? persistenceService.proposeTool(
                        turn, stepNumber, call.ordinal(), toolName, argumentsJson)
                : persistenceService.proposeTool(
                        turn, stepNumber, call.ordinal(), toolName, argumentsJson,
                        thoughtSignature);
        publishToolStep(turn, new AiChatStepFrameDto(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                stepNumber, "step", toolName,
                "proposed", null));
        try {
            requireCurrentToolExecution(turn);
            AiAssistantToolsetLoader.Load load = findTools
                    ? toolsetLoader.load(call.tool().args(), state.loadedToolsets)
                    : null;
            AiAssistantToolResult toolResult = load != null
                    ? load.result()
                    : toolExecutor.execute(
                            toolName, call.tool().args(), context.resources(),
                            turn.includePrivateNotes(), turn.scope());
            ToolTurn admittedTurn = new ToolTurn(
                    stepNumber, call.ordinal(), toolName, toolResult);
            state.toolBudgetAudit = requireAdditionalToolCapacity(
                    nativeTools,
                    state.toolTurns,
                    admittedTurn,
                    state.nativeCalls,
                    context.maskingContext(),
                    context.budget());
            String resultJson = promptAssembler.durableToolResult(
                    toolResult, state.toolBudgetAudit);
            String progressResultJson = promptAssembler.durableToolResult(toolResult);
            if (!persistenceService.finishTool(
                    turn, toolCallId, "executed", resultJson)) {
                failTool(turn, toolCallId, "turn_not_active");
                return new StepCallOutcome.Fail(INTERNAL_ERROR);
            }
            if (load != null) {
                state.loadedToolsets.add(load.loaded());
            }
            publishToolStep(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    stepNumber, "step", toolName,
                    "executed", null));
            boolean publishedPlan = "set_todos".equals(toolName);
            if (publishedPlan) {
                state.todos.clear();
                state.todos.addAll(AiChatTodo.from(
                        call.tool().args().get("items"),
                        call.tool().args().get("statuses")));
                // Requester-only, like narration: a plan step is model prose that can name a
                // record this member's own tool results reached, and a viewer whose access is
                // narrower must not learn it live from a frame the settled transcript would
                // withhold.
                publish(turn.userId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "todos", null, null, null, null,
                        serialize(objectMapper.valueToTree(state.todos))));
            }
            if (!findTools) {
                state.toolResultCache.put(toolCallKey, toolResult);
            }
            boolean freshResult = state.seenToolResults.add(progressResultJson);
            // Publishing a plan is bookkeeping, not evidence. Letting it reset the no-progress
            // guard would let a model keep a turn alive on cosmetically different plans alone, so
            // a plan leaves the guard exactly as it found it.
            if (!publishedPlan) {
                if (freshResult) {
                    state.noProgressSteps = 0;
                } else {
                    state.noProgressSteps++;
                }
            }
            state.toolTurns.add(admittedTurn);
            if (state.noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                return noProgressOutcome(closingAttempted);
            }
        } catch (AiAssistantLoopException exception) {
            failTool(turn, toolCallId, exception.detailReason());
            publishToolStep(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    stepNumber, "step", toolName,
                    "failed", exception.detailReason()));
            if (exception.recoverable()) {
                state.noProgressSteps++;
                ToolTurn refusedTurn = new ToolTurn(
                        stepNumber, call.ordinal(), toolName,
                        refusedToolResult(exception.detailReason()));
                try {
                    state.toolBudgetAudit = requireAdditionalToolCapacity(
                            nativeTools, state.toolTurns, refusedTurn, state.nativeCalls,
                            context.maskingContext(), context.budget());
                    state.toolTurns.add(refusedTurn);
                } catch (AiAssistantLoopException capacity) {
                    if (!closingAttempted
                            && CLOSABLE_REASONS.contains(capacity.terminalReason())) {
                        return new StepCallOutcome.Close(capacity.terminalReason());
                    }
                    throw capacity;
                }
                if (state.noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                    return noProgressOutcome(closingAttempted);
                }
                return CONTINUE;
            }
            if (!closingAttempted
                    && CLOSABLE_REASONS.contains(exception.terminalReason())) {
                return new StepCallOutcome.Close(exception.terminalReason());
            }
            return new StepCallOutcome.Fail(exception.terminalReason());
        } catch (RuntimeException exception) {
            String reason = toolFailureReason(exception);
            failTool(turn, toolCallId, reason);
            publishToolStep(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    stepNumber, "step", toolName,
                    "failed", reason));
            return new StepCallOutcome.Fail(reason);
        }
        return CONTINUE;
    }

    /**
     * Names what a turn that stopped making progress must do next.
     *
     * @param closingAttempted whether the turn already spent its closing step
     * @return the closing step when one is left, and the failure otherwise
     */
    private static StepCallOutcome noProgressOutcome(boolean closingAttempted) {
        return closingAttempted
                ? new StepCallOutcome.Fail("no_progress")
                : new StepCallOutcome.Close("no_progress");
    }

    /**
     * Stops renewing the turn's lease without releasing it.
     *
     * <p>The lease stays held on purpose. The durable terminal write runs after this method, in a
     * different call stack, and releases the lease there; releasing it here would open a window
     * in which the turn is running and unleased, which is the window the lease exists to close.
     * Dropping the local token alongside is safe for the same reason it is necessary: the terminal
     * write's licence to release is the terminal row it just changed, not a token, so it releases
     * the lease with or without one — while a turn whose terminal write never lands would
     * otherwise leave its token in this instance's memory for the life of the process.
     */
    private void stopHeartbeat(AutoCloseable heartbeat) {
        if (heartbeat == null) {
            return;
        }
        try {
            heartbeat.close();
        } catch (Exception exception) {
            log.warn("Assistant turn lease heartbeat did not stop cleanly exceptionClass={}",
                    exception.getClass().getName());
        }
    }

    /**
     * Resolves the toolsets a routed declaration wants its synthesis step to start from.
     *
     * <p>Seeding spends from the one {@code MAX_ACTIVE_TOOLSETS_PER_TURN} budget a turn has, not
     * from a second allowance beside it: the set's own size is the counter, so a turn seeded to
     * the cap is refused its next {@code find_tools} exactly as a turn that loaded its way there
     * is, and {@code reservationToolsets()} stays a strict upper bound on the vocabulary any
     * reachable turn can send. {@code SkillSpec} enforces the cap and the key vocabulary where a
     * skill is declared, so an undeclarable key cannot reach a running turn; the lookup still
     * fails closed rather than silently seeding nothing.
     *
     * <p>Called only from inside the {@code execution.executed()} branch, beside the durable
     * {@code applySkill} write. That coupling is the whole of the reconstruction contract for a
     * seeded turn: a reader rebuilding the loaded set from {@code ai_chat_turn.skill_key} and
     * {@code skill_version} plus the turn's {@code find_tools} rows sees a routed-but-unexecuted
     * turn as core-only, which is exactly what the turn held.
     *
     * <p>Resolved <strong>before</strong> that write, and never after it. This lookup fails closed,
     * so resolving it second would let a declaration carrying an unresolvable key persist the
     * attribution and then settle the turn as an internal error — leaving a reader to reconstruct
     * a loaded set for a turn that held nothing, the one disagreement the coupling exists to make
     * impossible. The refusal has to precede the row, not follow it.
     *
     * <p>{@code applySkill} returns whether its status-predicated update matched, and this call
     * site deliberately does not branch on it: the same transaction takes the turn's row lock
     * through {@code lockAuthorizedTurn(turn, RUNNING)} before the update, so a zero-row result is
     * unreachable for a caller that reached the method at all. The discarded boolean is that stated
     * invariant. A future caller that reaches {@code applySkill} without the lock would break it,
     * which is why the invariant is recorded on the method itself.
     *
     * @param skill the routed declaration whose plan produced the evidence being synthesized
     * @return the non-core toolsets the turn starts holding
     */
    private static Set<Toolset> seededToolsets(AiSkillCatalog.SkillSpec skill) {
        Set<Toolset> seeded = new LinkedHashSet<>();
        for (String key : skill.toolsets()) {
            seeded.add(Objects.requireNonNull(
                    AiAssistantToolCatalog.loadableByKey(key),
                    () -> skill.key() + " declares an unknown toolset " + key));
        }
        return seeded;
    }

    /**
     * Refuses a synthesis-step tool that would exceed the routed skill's write authority.
     *
     * <p>The invariant this guard exists for is that a routed turn cannot gain WRITE authority its
     * skill never declared: a write tool runs only when the skill both lists it in
     * {@code allowedTools} and carries an authority above {@code READ}. Read tools always pass —
     * they grant nothing the caller's own generic loop would not, and their scope honesty is
     * governed by the declared-scope tool policy, not by this guard. Refusing reads here made
     * every synthesis-time lookup fatal for skills whose {@code allowedTools} name only
     * server-side plan steps, which is how the daily brief failed on its first real question.
     */
    private void requireSkillAuthority(AiSkillCatalog.SkillSpec skill, String toolName) {
        if (skill == null || !toolCatalog.isWrite(toolName)) {
            return;
        }
        boolean writePermitted = skill.authority() != AiSkillCatalog.Authority.READ
                && skill.allowedTools().contains(toolName);
        if (!writePermitted) {
            throw new AiAssistantLoopException(
                    TOOL_OUTSIDE_SKILL_AUTHORITY, TOOL_OUTSIDE_SKILL_AUTHORITY);
        }
    }

    /**
     * Refuses a declared tool whose toolset this turn has not loaded.
     *
     * <p>Purely narrowing per-turn state, never an authorization decision: every tool in every
     * loadable toolset is already reachable on a routed or generic turn, so a load restores reach
     * rather than granting any. {@code requireSkillAuthority} runs first and keeps owning write
     * authority, so a write tool that is both unloaded and outside its skill's declaration still
     * settles as {@code tool_outside_skill_authority} rather than being relabelled here.
     *
     * <p>Raised from inside the {@code validateReferences} try so it lands on the recoverable
     * refusal branch: the model is told {@code tool_not_loaded} and can spend a step on
     * {@code find_tools}, and the turn keeps the closing step it is entitled to. An unknown name
     * deliberately falls through to {@code validateReferences}, which still names it
     * {@code unknown_tool}.
     *
     * <p>On both protocols this is a backstop. The raw step guard already rejects an unloaded name
     * before parsing, and the native provider only ever receives the loaded definitions.
     *
     * <p>It runs before {@code recordNativeCall} on purpose. The refusal leaves the loaded set
     * unchanged, so the next step's definitions still exclude the refused name; a recorded call
     * and its refused result would then be replayed into an {@code AiNativeToolRequest} whose
     * membership check rejects it with an {@code IllegalArgumentException} — an internal error in
     * place of the recoverable refusal this check exists to produce. Refusing first means the step
     * leaves a durable proposed-and-failed row and no replayable exchange at all. Keep that order.
     *
     * @param loadedToolsets the toolsets the turn currently holds
     * @param toolName the declared tool the step proposed
     */
    private void requireToolsetLoaded(Set<Toolset> loadedToolsets, String toolName) {
        if (toolCatalog.isKnown(toolName) && !toolCatalog.isLoaded(toolName, loadedToolsets)) {
            throw AiAssistantLoopException.refusedArguments("tool_not_loaded");
        }
    }

    /**
     * Publishes one tool-step milestone, except for {@code find_tools}.
     *
     * <p>{@code find_tools} reads nothing, so it has no coverage category: mapping it would raise
     * an {@code other} milestone live that {@code AiChatProgressService.project} omits on reload.
     * Publishing nothing and skipping the projected row keeps the live strip and the settled
     * transcript identical. The requester still sees the turn is alive through the existing
     * thinking and narration channels.
     *
     * @param turn the running turn
     * @param frame the step milestone the loop would otherwise publish
     */
    private void publishToolStep(AiChatQueuedTurn turn, AiChatStepFrameDto frame) {
        if (AiAssistantToolCatalog.FIND_TOOLS.equals(frame.tool())) {
            return;
        }
        publish(turn, frame);
    }

    /**
     * Names the budget a turn exhausted.
     *
     * <p>A routed turn is clamped to its skill's small synthesis budget, which is a different
     * failure from the generic loop running out of improvisation steps: telling a member to narrow
     * their scope is the wrong advice when the cohort read already succeeded and only the write-up
     * did not converge.
     */
    /**
     * Whether the step about to run is the last one the turn is allowed.
     *
     * <p>The last permitted step becomes the closing step rather than one more investigation whose
     * result no step remains to read. Spending it on an answer costs the turn nothing it could have
     * kept, and it holds every cap exactly: the closing step is inside the budget, never an extra
     * provider call beyond it. A turn allowed only one step keeps it, because a model that has read
     * nothing has nothing to close over.
     *
     * @param consumedSteps model decisions charged to the budget so far
     * @param maxSteps the turn's step allowance
     * @param stepNumber the durable number of the step about to run
     * @return true when no further step would follow this one
     */
    private static boolean lastPermittedStep(
            int consumedSteps, int maxSteps, int stepNumber) {
        return consumedSteps >= 1
                && (consumedSteps + 1 >= maxSteps || stepNumber >= HARD_MAX_STEPS);
    }

    private static String exhaustionReason(
            int maxSteps, int lastStepNumber, boolean routedSkill) {
        if (lastStepNumber >= HARD_MAX_STEPS || maxSteps == HARD_MAX_STEPS) {
            return "agent_backstop_exceeded";
        }
        return routedSkill ? "skill_budget_exceeded" : "step_cap_exceeded";
    }

    private static String screenedFinalText(String text) {
        return SpecialCareTextScreen.screen(text).excluded()
                ? MaskingEngine.OMITTED_BY_POLICY
                : text;
    }

    private static boolean excludedGeneratedText(String value) {
        return value != null && SpecialCareTextScreen.screen(value).excluded();
    }

    private boolean restrictionsChanged(AiChatQueuedTurn turn) {
        return restrictionEpoch.current(turn.workspaceId()) != turn.restrictionEpoch();
    }

    private void applyGeneratedTitle(AiChatQueuedTurn turn, String title) {
        String normalized = normalizeGeneratedTitle(title);
        if (normalized == null) {
            return;
        }
        try {
            persistenceService.applyGeneratedTitle(turn, normalized);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    static String normalizeGeneratedTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()
                || AiAssistantStepGuard.containsHandle(normalized)
                || AiAssistantStepGuard.containsControlInstruction(normalized)) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) <= MAX_GENERATED_TITLE_CHARS) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, MAX_GENERATED_TITLE_CHARS);
        return normalized.substring(0, end).stripTrailing();
    }

    private void requireCurrentAccess(AiChatQueuedTurn turn) {
        requireWorkspaceEnabled(turn);
        if (restrictionsChanged(turn)) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
        try {
            if (!workspaceService.isMember(turn.workspaceId(), turn.userId())) {
                throw new ForbiddenException("Workspace membership is no longer active");
            }
            workspaceService.requirePermission(
                    turn.workspaceId(), turn.userId(), Permission.AI_USE);
        } catch (ForbiddenException exception) {
            throw new AiAssistantLoopException("access_revoked", "access_revoked");
        }
    }

    private void requireWorkspaceEnabled(AiChatQueuedTurn turn) {
        if (!governanceService.isEnabled(turn.workspaceId())) {
            throw new AiAssistantLoopException("workspace_disabled", "workspace_disabled");
        }
    }

    private void requireCurrentToolExecution(AiChatQueuedTurn turn) {
        requireCurrentAccess(turn);
        persistenceService.requireRunning(turn);
    }

    private static void requireOwnership(AiRunLeaseGuard ownership) {
        if (ownership.isStopped()) {
            throw new AiAssistantLoopException(
                    AiAssistantTerminalReasons.OWNER_LOST, AiAssistantTerminalReasons.OWNER_LOST);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Assistant durable metadata could not be serialized", exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value instanceof ObjectNode object) {
            ObjectNode canonical = objectMapper.createObjectNode();
            object.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.set(
                            entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode canonical = objectMapper.createArrayNode();
            for (JsonNode item : array) {
                canonical.add(canonicalize(item));
            }
            return canonical;
        }
        return value.deepCopy();
    }

    private boolean deadlineReached(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }

    private void publish(AiChatQueuedTurn turn, AiChatStepFrameDto frame) {
        realtimeDispatcher.sessionNow(
                turn.workspaceId(), turn.sessionId(), AiChatProgressService.sharedFrame(frame));
    }

    private void publish(int userId, AiChatStepFrameDto frame) {
        realtimeDispatcher.userAfterCommit(userId, AiChatProgressService.viewerFrame(frame));
    }

    /**
     * Streams one step's normalized reasoning to the requester as an ephemeral thinking frame.
     *
     * <p>The reasoning here already survived the full normalization pipeline — special-care
     * screening, bounded length, the strict outbound leak scan, and demasking — so it is safe to
     * show the member who asked. It is deliberately never persisted (the V186 purge precedent):
     * thinking is a live view of the current turn, gone on refresh, and shared-session viewers
     * never receive it because the frame goes to the requester's queue only.
     */
    private void publishThinking(
            AiChatQueuedTurn turn, int stepNumber, Optional<String> reasoning) {
        reasoning.filter(text -> !text.isBlank()).ifPresent(text ->
                realtimeDispatcher.userAfterCommit(turn.userId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stepNumber, "thinking", null, null, null, null, text)));
    }

    private void failTool(AiChatQueuedTurn turn, int toolCallId, String reason) {
        persistenceService.failTool(
                turn, toolCallId, serialize(Map.of("reason", reason)));
    }

    private static String toolFailureReason(RuntimeException exception) {
        if (exception instanceof AiBudgetExhaustedException) {
            return "budget_exhausted";
        }
        if (exception instanceof TooManyRequestsException) {
            return "quota_exhausted";
        }
        if (exception instanceof ForbiddenException) {
            return "access_revoked";
        }
        log.warn("Assistant tool step failed exceptionClass={}",
                exception.getClass().getName());
        return INTERNAL_ERROR;
    }

    /**
     * The correctable error result the loop feeds back after a recoverable argument refusal.
     *
     * <p>Carries only the stable refusal reason: no CRM data was read and no provider content is
     * echoed, so the model learns exactly why the call was refused and nothing else.
     *
     * @param detailReason stable argument-refusal detail
     * @return tool result naming the refusal
     */
    private static AiAssistantToolResult refusedToolResult(String detailReason) {
        return new AiAssistantToolResult(Map.of("error", detailReason), List.of());
    }

    private static int inputTokens(AiStructuredOutcome<?> outcome) {
        return switch (outcome) {
            case AiStructuredOutcome.Parsed<?> parsed -> parsed.inputTokens();
            case AiStructuredOutcome.Malformed<?> malformed -> malformed.inputTokens();
        };
    }

    private static int outputTokens(AiStructuredOutcome<?> outcome) {
        return switch (outcome) {
            case AiStructuredOutcome.Parsed<?> parsed -> parsed.outputTokens();
            case AiStructuredOutcome.Malformed<?> malformed -> malformed.outputTokens();
        };
    }

    private static int addTokens(int current, int additional) {
        if (additional <= 0) {
            return current;
        }
        return additional > Integer.MAX_VALUE - current
                ? Integer.MAX_VALUE
                : current + additional;
    }

    private ToolBudgetAudit requireAdditionalToolCapacity(
            boolean nativeTools,
            List<ToolTurn> toolTurns,
            ToolTurn prospectiveTurn,
            Map<AiAssistantToolCallRef, AiToolCall> nativeCalls,
            MaskingContext maskingContext,
            AiAssistantPromptBudget budget) {
        if (nativeTools) {
            return promptAssembler.requireAdditionalNativeExchangeCapacity(
                    toolTurns,
                    prospectiveTurn,
                    nativeCalls,
                    maskingContext,
                    budget);
        }
        return promptAssembler.requireAdditionalToolResultCapacity(
                toolTurns,
                prospectiveTurn,
                maskingContext,
                budget);
    }

    private static void recordNativeCall(
            boolean nativeTools,
            Map<AiAssistantToolCallRef, AiToolCall> nativeCalls,
            AiAssistantToolCallRef ref,
            Optional<AiToolCall> providerCall) {
        if (!nativeTools) {
            return;
        }
        AiToolCall call = providerCall.orElseThrow(
                () -> new IllegalStateException("Native tool call is unavailable"));
        if (nativeCalls.putIfAbsent(ref, call) != null) {
            throw new IllegalStateException("Native tool call was already recorded");
        }
    }

    private static void resetMalformedStream(
            AiChatStreamingProgress progress,
            AiChatStreamingProgress.Observer observer) {
        if (progress != null && observer != null && observer.hasProjectedText()) {
            progress.reset();
        }
    }

    /**
     * Translates one native completion into the step attempt the loop's single path consumes.
     *
     * <p>A response carrying several calls is refused here, under the same
     * {@code native_multiple_calls} repair rule the parse boundary used to raise for it. The
     * boundary now bounds a response by what the operator declared for the endpoint rather than by
     * the literal one, so a declared endpoint's batch reaches this method intact — and this loop
     * cannot yet execute a batch under one per-call authorization, ownership, deadline and budget
     * admission. Refusing it keeps the model's instruction and the turn's outcome exactly what an
     * over-delivering provider has always produced, rather than executing the first call of a
     * decision the model made as four.
     */
    private static NativeStepAttempt nativeStepAttempt(
            AiNativeToolCompletion<AiAssistantStep.FinalAnswer> completion) {
        return switch (completion) {
            case AiNativeToolCompletion.Tool<AiAssistantStep.FinalAnswer> tool
                    when tool.providerCalls().size() > 1 -> new NativeStepAttempt(
                    new AiStructuredRepairAttempt<>(
                            new AiStructuredOutcome.Malformed<>(
                                    AiStructuredOutcome.REASON_MALFORMED,
                                    tool.inputTokens(),
                                    tool.outputTokens(),
                                    tool.stopReason()),
                            Optional.of(AiStructuredRepair.from("native_multiple_calls", "")),
                            tool.reasoning()),
                    Optional.empty(),
                    true);
            case AiNativeToolCompletion.Tool<AiAssistantStep.FinalAnswer> tool -> {
                AiAssistantStep step = new AiAssistantStep(
                        new AiAssistantStep.Tool(
                                tool.providerCall().name(), tool.arguments()),
                        null);
                AiStructuredOutcome<AiAssistantStep> outcome =
                        new AiStructuredOutcome.Parsed<>(
                                step,
                                tool.demaskWarnings(),
                                tool.inputTokens(),
                                tool.outputTokens(),
                                tool.stopReason());
                yield new NativeStepAttempt(
                        new AiStructuredRepairAttempt<>(
                                outcome, Optional.empty(), tool.reasoning()),
                        Optional.of(tool.providerCall()),
                        false,
                        tool.narration());
            }
            case AiNativeToolCompletion.Content<AiAssistantStep.FinalAnswer> content -> {
                AiStructuredRepairAttempt<AiAssistantStep.FinalAnswer> source = content.attempt();
                AiStructuredOutcome<AiAssistantStep> outcome = switch (source.outcome()) {
                    case AiStructuredOutcome.Parsed<AiAssistantStep.FinalAnswer> parsed ->
                            new AiStructuredOutcome.Parsed<>(
                                    new AiAssistantStep(null, parsed.value()),
                                    parsed.demaskWarnings(),
                                    parsed.inputTokens(),
                                    parsed.outputTokens(),
                                    parsed.stopReason());
                    case AiStructuredOutcome.Malformed<AiAssistantStep.FinalAnswer> malformed ->
                            new AiStructuredOutcome.Malformed<>(
                                    malformed.reason(),
                                    malformed.inputTokens(),
                                    malformed.outputTokens(),
                                    malformed.stopReason());
                };
                yield new NativeStepAttempt(
                        new AiStructuredRepairAttempt<>(
                                outcome, source.repair(), source.reasoning()),
                        Optional.empty(),
                        false);
            }
            case AiNativeToolCompletion.Malformed<AiAssistantStep.FinalAnswer> malformed -> {
                AiStructuredOutcome<AiAssistantStep> outcome =
                        new AiStructuredOutcome.Malformed<>(
                                AiStructuredOutcome.REASON_MALFORMED,
                                malformed.inputTokens(),
                                malformed.outputTokens(),
                                malformed.stopReason());
                yield new NativeStepAttempt(
                        new AiStructuredRepairAttempt<>(
                                outcome,
                                Optional.of(AiStructuredRepair.from(
                                        malformed.repairRule(), "")),
                                malformed.reasoning()),
                        Optional.empty(),
                        true);
            }
        };
    }

    /**
     * The per-turn surfaces every tool call of one turn executes against.
     *
     * <p>Bundled rather than passed one by one so the extracted per-call body reads as the decision
     * it makes instead of the context it carries. Every component is settled before the first model
     * step and never replaced, which is what makes bundling them safe.
     *
     * @param turn the running turn
     * @param ownership the run-lease guard this loop instance holds
     * @param deadline the turn's wall-clock deadline
     * @param maskingContext the turn's request-local placeholder bindings
     * @param resources the turn's per-turn record handles
     * @param budget the turn's prompt allocation
     * @param attachments the turn's untrusted attachment data
     */
    private record ToolExecutionContext(
            AiChatQueuedTurn turn,
            AiRunLeaseGuard ownership,
            Instant deadline,
            MaskingContext maskingContext,
            AiChatResourceRegistry resources,
            AiAssistantPromptBudget budget,
            List<Map<String, Object>> attachments) {
    }

    /**
     * The tool state one turn accumulates across its model steps.
     *
     * <p>Deliberately mutable and owned by {@link #run}: each step's calls advance it and the next
     * step's prompt assembly reads it, so the step loop and the extracted per-call body must share
     * one instance rather than copies of its parts.
     */
    private static final class TurnToolState {
        private final List<ToolTurn> toolTurns = new ArrayList<>();
        private final Map<AiAssistantToolCallRef, AiToolCall> nativeCalls = new HashMap<>();
        private final Map<String, AiAssistantToolResult> toolResultCache = new HashMap<>();
        private final Set<String> seenToolResults = new HashSet<>();
        private final Set<Toolset> loadedToolsets =
                new LinkedHashSet<>(AiAssistantToolCatalog.CORE);
        private final List<AiChatTodo> todos = new ArrayList<>();
        private int noProgressSteps;
        private int planPublications;
        private ToolBudgetAudit toolBudgetAudit = ToolBudgetAudit.NONE;
    }

    /**
     * What the step loop must do once one tool call has settled.
     *
     * <p>Sealed so every exit the extracted per-call body can take is named and the compiler, not a
     * reader, proves the step loop handles all of them.
     */
    private sealed interface StepCallOutcome {

        /** The call settled and the turn may take its next step. */
        record Continue() implements StepCallOutcome {
        }

        /** The turn must spend its closing step, settling on the named reason if that fails. */
        record Close(String reason) implements StepCallOutcome {
        }

        /** The turn ends as failed with the named terminal reason. */
        record Fail(String reason) implements StepCallOutcome {
        }

        /** The turn ends as timed out with the named terminal reason. */
        record TimedOut(String reason) implements StepCallOutcome {
        }
    }

    private record NativeStepAttempt(
            AiStructuredRepairAttempt<AiAssistantStep> attempt,
            Optional<AiToolCall> providerCall,
            boolean malformed,
            Optional<String> narration) {

        private NativeStepAttempt(
                AiStructuredRepairAttempt<AiAssistantStep> attempt,
                Optional<AiToolCall> providerCall,
                boolean malformed) {
            this(attempt, providerCall, malformed, Optional.empty());
        }

        private NativeStepAttempt {
            java.util.Objects.requireNonNull(attempt, "attempt");
            providerCall = java.util.Objects.requireNonNull(providerCall, "providerCall");
        }
    }
}
