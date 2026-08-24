package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
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
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.provider.AiImageInputUnsupportedException;
import ooo.klae.connex.backend.ai.provider.AiInvocationProtocol;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderCallerDeadlineExceededException;
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
@Service
@RequiredArgsConstructor
public class AiChatAgentLoopService {
    static final int HARD_MAX_STEPS = 64;
    private static final int MAX_CONSECUTIVE_NO_PROGRESS_STEPS = 2;
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
    private final AiAssistantWriteToolService writeToolService;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiSkillRouter skillRouter;
    private final AiSkillPlanRunner skillPlanRunner;
    private final AiChatMemoryService memoryService;
    private final AiChatAttachmentContextService attachmentContextService;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiChatProgressService progressService;
    private final AiChatCitationProjector citationProjector;
    private final AiRestrictionEpoch restrictionEpoch;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final AiWorkspaceGovernanceService governanceService;
    private final Clock clock;

    /** Runs one committed turn under the shared generation context. */
    public AiGenerationTaskResult<AiChatTurnGenerationResult> run(AiChatQueuedTurn turn) {
        try {
            requireWorkspaceEnabled(turn);
            boolean running;
            try {
                running = persistenceService.markRunning(turn);
            } catch (ForbiddenException exception) {
                return AiGenerationTaskResult.failed("access_revoked");
            }
            if (!running) {
                return AiGenerationTaskResult.failed(INTERNAL_ERROR);
            }
            Instant deadline = clock.instant().plus(AiAssistantTurnBudget.TURN);
            publish(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    0, "state", null, "running", null));
            MaskingContext maskingContext = new MaskingContext(turn.privacyMode());
            AiChatResourceRegistry resources = new AiChatResourceRegistry(maskingContext);
            AiChatStreamingProgress streamingProgress = turn.streamed()
                    ? new AiChatStreamingProgress(turn, persistenceService)
                    : null;
            AiChatMemory memory = memoryService.prepare(turn, maskingContext, deadline);
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
            AiChatAttachmentContext attachmentContext =
                    attachmentContextService.prepare(turn, deadline, maskingContext);
            List<ToolTurn> toolTurns = new ArrayList<>();
            Map<Integer, AiToolCall> nativeCalls = new HashMap<>();
            boolean nativeTools = memory.nativeTools();
            List<AiToolDefinition> nativeDefinitions = nativeTools
                    ? promptAssembler.nativeToolDefinitions()
                    : List.of();
            Map<String, AiAssistantToolResult> toolResultCache = new HashMap<>();
            Set<String> seenToolResults = new HashSet<>();
            AiStructuredRepair repair = null;
            Integer nativeToolsDegradedStatus = null;
            ToolBudgetAudit toolBudgetAudit = ToolBudgetAudit.NONE;
            int nativeProviderAttempts = 0;
            int noProgressSteps = 0;
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
                AiSkillPlanRunner.Execution execution = skillPlanRunner.run(
                        turn,
                        routing,
                        turn.scope(),
                        resources,
                        memory.budget().toolResultBytes(),
                        () -> requireCurrentToolExecution(turn));
                // Every step the plan consumed already owns a durable idempotency key, so the
                // model loop resumes after them even when the plan produced nothing usable.
                stepOffset = execution.lastStepNumber();
                maxSteps = Math.min(maxSteps, HARD_MAX_STEPS - stepOffset);
                if (execution.executed()) {
                    // Attribution is written only once the plan actually produced the evidence the
                    // answer is built from, so the durable turn row and the answer's own skill
                    // metadata can never name a declaration the turn did not really run under.
                    persistenceService.applySkill(
                            turn, routing.skill().key(), routing.skill().version());
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
                    MaskedPrompt prompt = nativeTools
                            ? promptAssembler.assembleNative(
                                    history,
                                    pageContext,
                                    toolTurns,
                                    maskingContext,
                                    resources,
                                    attachmentContext.data(),
                                    memory.budget(),
                                    stepContext)
                            : promptAssembler.assemble(
                                    history,
                                    pageContext,
                                    toolTurns,
                                    maskingContext,
                                    resources,
                                    attachmentContext.data(),
                                    memory.budget(),
                                    stepRepair,
                                    stepContext);
                    AiAssistantPromptAssembler.NativeReplay nativeReplay = nativeTools
                            ? promptAssembler.nativeReplay(
                                    toolTurns,
                                    nativeCalls,
                                    maskingContext,
                                    memory.budget(),
                                    stepRepair)
                            : new AiAssistantPromptAssembler.NativeReplay(
                                    List.of(), null, ToolBudgetAudit.NONE);
                    if (nativeTools) {
                        toolBudgetAudit = nativeReplay.audit();
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
                    AiRawOutputGuard outputGuard = stepGuard.forIssuedPlaceholders(
                            maskingContext.tokenBindings().stream()
                                    .map(Map.Entry::getKey)
                                    .collect(Collectors.toUnmodifiableSet()));
                    boolean degradationEligible = nativeTools
                            && nativeProviderAttempts == 0
                            && toolTurns.isEmpty()
                            && nativeCalls.isEmpty();
                    boolean nativeMalformed = false;
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
                                    nativeReplay.repairMessage());
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
                        } else {
                            attempt = invocationService.completeStructuredRepairable(
                                    invocation,
                                    AiAssistantStep.class,
                                    outputGuard,
                                    stepSchema.responseSchema(),
                                    admission,
                                    providerGuard);
                        }
                    } catch (AiProviderRequestRejectedException exception) {
                        if (!degradationEligible || !exception.isClientError()) {
                            throw exception;
                        }
                        nativeTools = false;
                        nativeToolsDegradedStatus = exception.statusCode();
                        nativeCalls.clear();
                        toolTurns.clear();
                        stepRepair = null;
                        repair = null;
                        continue;
                    }
                    outcome = java.util.Objects.requireNonNull(attempt, "attempt").outcome();
                    requireWorkspaceEnabled(turn);
                    persistenceService.requireRunning(turn);
                    inputTokens = addTokens(inputTokens, inputTokens(outcome));
                    outputTokens = addTokens(outputTokens, outputTokens(outcome));
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
                    // the turn above, and the backstop bounds the step numbers regardless.
                    repair = attempt.repair().orElseThrow();
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
                    if (closing) {
                        return AiGenerationTaskResult.failed(closingReason);
                    }
                    requireSkillAuthority(activeSkill, step.tool().name());
                    if (streamingObserver != null) {
                        streamingObserver.requireNoTerminalText();
                    }
                    if (deadlineReached(deadline)) {
                        return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                    }
                    requireCurrentAccess(turn);
                    toolExecutor.validateReferences(
                            step.tool().name(), step.tool().args(), resources);
                    String argumentsJson = serialize(step.tool().args());
                    String toolCallKey = step.tool().name() + "\n"
                            + serialize(canonicalize(step.tool().args()));
                    recordNativeCall(
                            nativeTools, nativeCalls,
                            stepNumber, nativeProviderCall);
                    String thoughtSignature = nativeProviderCall
                            .map(AiToolCall::thoughtSignature)
                            .orElse(null);
                    AiAssistantToolResult cachedResult = toolResultCache.get(toolCallKey);
                    if (cachedResult != null) {
                        noProgressSteps++;
                        if (noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                            if (closingAttempted) {
                                return AiGenerationTaskResult.failed("no_progress");
                            }
                            closingAttempted = true;
                            closingPending = true;
                            closingReason = "no_progress";
                            continue steps;
                        }
                        ToolTurn cachedTurn = new ToolTurn(
                                stepNumber, step.tool().name(), cachedResult);
                        toolBudgetAudit = requireAdditionalToolCapacity(
                                nativeTools,
                                toolTurns,
                                cachedTurn,
                                nativeCalls,
                                maskingContext,
                                memory.budget());
                        toolTurns.add(cachedTurn);
                        continue;
                    }
                    if (toolCatalog.isWrite(step.tool().name())) {
                        AiAssistantPreparedWrite write = writeToolService.prepare(
                                step.tool().name(), step.tool().args(), resources,
                                turn.restrictionEpoch());
                        if (!attachmentContext.data().isEmpty()
                                && write.tier() == AiAssistantToolCatalog.ToolTier.AUTO) {
                            return AiGenerationTaskResult.failed("attachment_auto_write_blocked");
                        }
                        AiAssistantToolProposal proposal = thoughtSignature == null
                                ? persistenceService.proposeWriteTool(turn, stepNumber, write)
                                : persistenceService.proposeWriteTool(
                                        turn, stepNumber, write, thoughtSignature);
                        int toolCallId = proposal.id();
                        publish(turn.userId(), new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "proposed", null, toolCallId));
                        try {
                            requireCurrentToolExecution(turn);
                            int guardedStepNumber = stepNumber;
                            boolean guardedNativeTools = nativeTools;
                            AiAssistantToolResult toolResult;
                            boolean replayed = false;
                            ToolBudgetAudit admittedToolBudgetAudit;
                            if (write.tier() == AiAssistantToolCatalog.ToolTier.AUTO) {
                                AiAssistantWriteToolService.WriteExecution execution =
                                        writeToolService.executeAuto(
                                                turn,
                                                toolCallId,
                                                candidate -> requireAdditionalToolCapacity(
                                                        guardedNativeTools,
                                                        toolTurns,
                                                        new ToolTurn(
                                                                guardedStepNumber,
                                                                step.tool().name(),
                                                                candidate),
                                                        nativeCalls,
                                                        maskingContext,
                                                        memory.budget()));
                                toolResult = execution.toolResult();
                                replayed = execution.replayed();
                                if (replayed) {
                                    ExecutedReplay executedReplay = nativeTools
                                            ? promptAssembler.withExecutedNativeReplay(
                                                    toolTurns,
                                                    new ToolTurn(
                                                            stepNumber,
                                                            step.tool().name(),
                                                            toolResult),
                                                    nativeCalls,
                                                    maskingContext,
                                                    memory.budget())
                                            : promptAssembler.withExecutedReplay(
                                                    toolTurns,
                                                    new ToolTurn(
                                                            stepNumber,
                                                            step.tool().name(),
                                                            toolResult),
                                                    maskingContext,
                                                    memory.budget());
                                    toolTurns.clear();
                                    toolTurns.addAll(executedReplay.toolTurns());
                                    toolResult = toolTurns.getLast().result();
                                    admittedToolBudgetAudit = executedReplay.audit();
                                } else {
                                    admittedToolBudgetAudit = requireAdditionalToolCapacity(
                                            nativeTools,
                                            toolTurns,
                                            new ToolTurn(
                                                    stepNumber,
                                                    step.tool().name(),
                                                    toolResult),
                                            nativeCalls,
                                            maskingContext,
                                            memory.budget());
                                }
                            } else {
                                toolResult = writeToolService.proposalResult(write, proposal);
                                admittedToolBudgetAudit = requireAdditionalToolCapacity(
                                        nativeTools,
                                        toolTurns,
                                        new ToolTurn(
                                                stepNumber,
                                                step.tool().name(),
                                                toolResult),
                                        nativeCalls,
                                        maskingContext,
                                        memory.budget());
                            }
                            toolBudgetAudit = admittedToolBudgetAudit;
                            String status = write.tier() == AiAssistantToolCatalog.ToolTier.AUTO
                                    ? "executed"
                                    : ("executed".equals(proposal.status())
                                            ? "executed"
                                            : "approval_required");
                            publish(turn.userId(), new AiChatStepFrameDto(
                                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                    stepNumber, "step", step.tool().name(),
                                    status, null, toolCallId));
                            String resultJson = promptAssembler.durableToolResult(toolResult);
                            toolResultCache.put(toolCallKey, toolResult);
                            if (seenToolResults.add(resultJson)) {
                                noProgressSteps = 0;
                            } else {
                                noProgressSteps++;
                            }
                            if (!replayed) {
                                toolTurns.add(new ToolTurn(
                                        stepNumber, step.tool().name(), toolResult));
                            }
                            if (noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                                if (closingAttempted) {
                                    return AiGenerationTaskResult.failed("no_progress");
                                }
                                closingAttempted = true;
                                closingPending = true;
                                closingReason = "no_progress";
                                continue steps;
                            }
                        } catch (AiAssistantLoopException exception) {
                            failTool(turn, toolCallId, exception.detailReason());
                            publish(turn.userId(), new AiChatStepFrameDto(
                                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                    stepNumber, "step", step.tool().name(),
                                    "failed", exception.detailReason(), toolCallId));
                            if (!closingAttempted
                                    && CLOSABLE_REASONS.contains(exception.terminalReason())) {
                                closingAttempted = true;
                                closingPending = true;
                                closingReason = exception.terminalReason();
                                continue steps;
                            }
                            return AiGenerationTaskResult.failed(exception.terminalReason());
                        } catch (RuntimeException exception) {
                            String reason = toolFailureReason(exception);
                            failTool(turn, toolCallId, reason);
                            publish(turn.userId(), new AiChatStepFrameDto(
                                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                    stepNumber, "step", step.tool().name(),
                                    "failed", reason, toolCallId));
                            return AiGenerationTaskResult.failed(reason);
                        }
                        continue;
                    }
                    int toolCallId = thoughtSignature == null
                            ? persistenceService.proposeTool(
                                    turn, stepNumber, step.tool().name(), argumentsJson)
                            : persistenceService.proposeTool(
                                    turn, stepNumber, step.tool().name(), argumentsJson,
                                    thoughtSignature);
                    publish(turn, new AiChatStepFrameDto(
                            turn.workspaceId(), turn.sessionId(), turn.turnId(),
                            stepNumber, "step", step.tool().name(),
                            "proposed", null));
                    try {
                        requireCurrentToolExecution(turn);
                        AiAssistantToolResult toolResult = toolExecutor.execute(
                                step.tool().name(), step.tool().args(), resources,
                                turn.includePrivateNotes(), turn.scope());
                        ToolTurn admittedTurn = new ToolTurn(
                                stepNumber, step.tool().name(), toolResult);
                        toolBudgetAudit = requireAdditionalToolCapacity(
                                nativeTools,
                                toolTurns,
                                admittedTurn,
                                nativeCalls,
                                maskingContext,
                                memory.budget());
                        String resultJson = promptAssembler.durableToolResult(
                                toolResult, toolBudgetAudit);
                        String progressResultJson = promptAssembler.durableToolResult(toolResult);
                        if (!persistenceService.finishTool(
                                turn, toolCallId, "executed", resultJson)) {
                            failTool(turn, toolCallId, "turn_not_active");
                            return AiGenerationTaskResult.failed(INTERNAL_ERROR);
                        }
                        publish(turn, new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "executed", null));
                        toolResultCache.put(toolCallKey, toolResult);
                        if (seenToolResults.add(progressResultJson)) {
                            noProgressSteps = 0;
                        } else {
                            noProgressSteps++;
                        }
                        toolTurns.add(admittedTurn);
                        if (noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                            if (closingAttempted) {
                                return AiGenerationTaskResult.failed("no_progress");
                            }
                            closingAttempted = true;
                            closingPending = true;
                            closingReason = "no_progress";
                            continue steps;
                        }
                    } catch (AiAssistantLoopException exception) {
                        failTool(turn, toolCallId, exception.detailReason());
                        publish(turn, new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "failed", exception.detailReason()));
                        if (!closingAttempted
                                && CLOSABLE_REASONS.contains(exception.terminalReason())) {
                            closingAttempted = true;
                            closingPending = true;
                            closingReason = exception.terminalReason();
                            continue steps;
                        }
                        return AiGenerationTaskResult.failed(exception.terminalReason());
                    } catch (RuntimeException exception) {
                        String reason = toolFailureReason(exception);
                        failTool(turn, toolCallId, reason);
                        publish(turn, new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "failed", reason));
                        return AiGenerationTaskResult.failed(reason);
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
                Optional<List<AiAssistantStep.AnswerBlock>> screenedBlocks =
                        screenedAnswerBlocks(finalAnswer.blocks());
                // The persisted transcript text stays exactly the screened terminal text that was
                // streamed to the requester. Rendering the blocks here instead would repaint the
                // answer with different prose the moment the transcript refreshed; the typed
                // document in structured_json is the surface that carries the blocks.
                if (screenedBlocks.isEmpty()) {
                    persistedText = MaskingEngine.OMITTED_BY_POLICY;
                    omitted = true;
                }
                List<String> citations = omitted ? List.of() : finalAnswer.citations();
                try {
                    resources.requireKnownCitations(citations);
                    if (!omitted) {
                        requireBlockCitations(screenedBlocks.orElseThrow(), citations);
                    }
                } catch (AiAssistantLoopException exception) {
                    resetMalformedStream(streamingProgress, streamingObserver);
                    throw exception;
                }
                List<String> suggestions = omitted
                        ? List.of()
                        : AiAssistantStepGuard.filterSuggestions(finalAnswer.suggestions());
                List<AiChatProgressItemDto> progress = progressService.project(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(), "resolved");
                AiAssistantStep.Coverage coverage = omitted
                        ? null
                        : AiChatProgressService.reconcileCoverage(
                                finalAnswer.coverage(), progress, toolBudgetAudit);
                Map<String, AiChatResourceRegistry.ResourceRef> citedResources =
                        resources.snapshot();
                String metadata = promptAssembler.finalMetadata(
                        turn.turnId(), citations, suggestions, citedResources,
                        citationProjector.observe(
                                turn.workspaceId(), citations, citedResources),
                        omitted ? List.of() : screenedBlocks.orElseThrow(),
                        coverage,
                        progress,
                        toolBudgetAudit,
                        skillReference);
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
            return AiGenerationTaskResult.failed(INTERNAL_ERROR);
        }
    }

    /**
     * Refuses a synthesis-step tool a routed skill never declared.
     *
     * <p>{@code allowedTools} and {@code authority} bound the whole turn, not just the server-owned
     * plan. Once a skill has run, the model's remaining steps exist to synthesize its evidence: a
     * read-authority skill must not be able to reach a write tool, and no skill may cause a tool it
     * never declared to run, however the model phrases the request.
     */
    private void requireSkillAuthority(AiSkillCatalog.SkillSpec skill, String toolName) {
        if (skill == null) {
            return;
        }
        boolean forbiddenWrite = skill.authority() == AiSkillCatalog.Authority.READ
                && toolCatalog.isWrite(toolName);
        if (forbiddenWrite || !skill.allowedTools().contains(toolName)) {
            throw new AiAssistantLoopException(
                    TOOL_OUTSIDE_SKILL_AUTHORITY, TOOL_OUTSIDE_SKILL_AUTHORITY);
        }
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

    private static Optional<List<AiAssistantStep.AnswerBlock>> screenedAnswerBlocks(
            List<AiAssistantStep.AnswerBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return Optional.empty();
        }
        for (AiAssistantStep.AnswerBlock block : blocks) {
            // Every free-text field a block can carry is screened, including structured rows:
            // an unscreened field is durably persisted into the answer document and rendered to
            // shared-session viewers, and special-care text must be excluded in both privacy modes.
            if (block == null
                    || excludedGeneratedText(block.title())
                    || excludedGeneratedText(block.body())
                    || block.items().stream().anyMatch(AiChatAgentLoopService::excludedGeneratedText)
                    || block.rows().stream().anyMatch(AiChatAgentLoopService::excludedGeneratedRow)) {
                return Optional.empty();
            }
        }
        return Optional.of(List.copyOf(blocks));
    }

    private static boolean excludedGeneratedRow(AiAssistantStep.Row row) {
        return row == null
                || excludedGeneratedText(row.label())
                || excludedGeneratedText(row.value())
                || excludedGeneratedText(row.detail())
                || excludedGeneratedText(row.at());
    }

    private static boolean excludedGeneratedText(String value) {
        return value != null && SpecialCareTextScreen.screen(value).excluded();
    }

    private static void requireBlockCitations(
            List<AiAssistantStep.AnswerBlock> blocks, List<String> citations) {
        Set<String> allowed = Set.copyOf(citations);
        if (blocks.stream().flatMap(block -> block.citations().stream())
                .anyMatch(citation -> !allowed.contains(citation))) {
            throw AiAssistantLoopException.malformed("unknown_citation");
        }
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
        return INTERNAL_ERROR;
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
            Map<Integer, AiToolCall> nativeCalls,
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
            Map<Integer, AiToolCall> nativeCalls,
            int stepNumber,
            Optional<AiToolCall> providerCall) {
        if (!nativeTools) {
            return;
        }
        AiToolCall call = providerCall.orElseThrow(
                () -> new IllegalStateException("Native tool call is unavailable"));
        if (nativeCalls.putIfAbsent(stepNumber, call) != null) {
            throw new IllegalStateException("Native tool call step was already recorded");
        }
    }

    private static void resetMalformedStream(
            AiChatStreamingProgress progress,
            AiChatStreamingProgress.Observer observer) {
        if (progress != null && observer != null && observer.hasProjectedText()) {
            progress.reset();
        }
    }

    private static NativeStepAttempt nativeStepAttempt(
            AiNativeToolCompletion<AiAssistantStep.FinalAnswer> completion) {
        return switch (completion) {
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
                        false);
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

    private record NativeStepAttempt(
            AiStructuredRepairAttempt<AiAssistantStep> attempt,
            Optional<AiToolCall> providerCall,
            boolean malformed) {

        private NativeStepAttempt {
            java.util.Objects.requireNonNull(attempt, "attempt");
            providerCall = java.util.Objects.requireNonNull(providerCall, "providerCall");
        }
    }
}
