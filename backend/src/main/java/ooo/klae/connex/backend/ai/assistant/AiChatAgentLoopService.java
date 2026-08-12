package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredRepair;
import ooo.klae.connex.backend.ai.AiStructuredRepairAttempt;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolTurn;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
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
    private static final Duration TURN_DEADLINE = Duration.ofSeconds(70);
    private static final int MAX_CONSECUTIVE_NO_PROGRESS_STEPS = 2;
    private static final String INTERNAL_ERROR = "internal_error";
    private static final int MAX_HISTORY_MESSAGES = 50;
    private static final int MAX_HISTORY_CHARS = 64_000;
    private static final int MAX_OUTPUT_TOKENS = 1200;
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final double TEMPERATURE = 0.1;

    private final AiInvocationService invocationService;
    private final AiInvocationAdmissionService invocationAdmissionService;
    private final AiAssistantStepGuard stepGuard;
    private final AiAssistantStepSchema stepSchema;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantIdentifierResolver identifierResolver;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiChatTurnPersistenceService persistenceService;
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
            Instant deadline = clock.instant().plus(TURN_DEADLINE);
            publish(turn, new AiChatStepFrameDto(
                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                    0, "state", null, "running", null));
            List<AiChatMessage> history = boundedHistory(
                    persistenceService.loadHistory(turn, MAX_HISTORY_MESSAGES), turn);
            AiChatResourceRegistry resources = new AiChatResourceRegistry();
            MaskingContext maskingContext = new MaskingContext();
            AiChatMessage initiatingMessage = history.stream()
                    .filter(message -> message.getId() == turn.userMessageId())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Assistant initiating message is unavailable"));
            identifierResolver.seed(initiatingMessage.getContent(), maskingContext);
            List<AiChatPageContextDto> promptContext =
                    new ArrayList<>(turn.pageContext());
            promptContext.addAll(promptAssembler.replayPageContext(history));
            AiAssistantToolResult pageContext = toolExecutor.pageContext(
                    promptContext, resources);
            List<ToolTurn> toolTurns = new ArrayList<>();
            Map<String, AiAssistantToolResult> toolResultCache = new HashMap<>();
            Set<String> seenToolResults = new HashSet<>();
            AiStructuredRepair repair = null;
            int noProgressSteps = 0;
            int inputTokens = 0;
            int outputTokens = 0;
            int maxSteps = Math.min(
                    governanceService.assistantMaxSteps(turn.workspaceId()), HARD_MAX_STEPS);

            for (int stepNumber = 1; stepNumber <= maxSteps; stepNumber++) {
                requireWorkspaceEnabled(turn);
                if (deadlineReached(deadline)) {
                    return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                }
                AiInvocation invocation = new AiInvocation(
                        AiFeature.ASSISTANT_CHAT,
                        maskingContext,
                        promptAssembler.assemble(
                                history, pageContext, toolTurns, maskingContext, resources, repair),
                        MAX_OUTPUT_TOKENS,
                        TEMPERATURE);
                AiRawOutputGuard outputGuard = stepGuard.forIssuedPlaceholders(
                        maskingContext.tokenBindings().stream()
                                .map(Map.Entry::getKey)
                                .collect(Collectors.toUnmodifiableSet()));
                AiStructuredRepairAttempt<AiAssistantStep> attempt;
                try (AiInvocationAdmissionService.DirectAdmission admission =
                        invocationAdmissionService.acquireDirect()) {
                    attempt = invocationService.completeStructuredRepairable(
                            invocation,
                            AiAssistantStep.class,
                            outputGuard,
                            stepSchema.responseSchema(),
                            admission);
                }
                AiStructuredOutcome<AiAssistantStep> outcome = attempt.outcome();
                requireWorkspaceEnabled(turn);
                inputTokens = addTokens(inputTokens, inputTokens(outcome));
                outputTokens = addTokens(outputTokens, outputTokens(outcome));
                if (deadlineReached(deadline)) {
                    return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                }
                if (outcome instanceof AiStructuredOutcome.Malformed<?>) {
                    if (repair != null || attempt.repair().isEmpty()) {
                        return AiGenerationTaskResult.failed("schema_repair_failed");
                    }
                    repair = attempt.repair().orElseThrow();
                    continue;
                }
                if (!(outcome instanceof AiStructuredOutcome.Parsed<?> parsed)
                        || !(parsed.value() instanceof AiAssistantStep step)) {
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                if (parsed.demaskWarnings() != 0) {
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                repair = null;
                if (step.tool() != null) {
                    if (deadlineReached(deadline)) {
                        return AiGenerationTaskResult.timedOut("turn_deadline_exceeded");
                    }
                    requireCurrentAccess(turn);
                    toolExecutor.validateReferences(
                            step.tool().name(), step.tool().args(), resources);
                    String argumentsJson = serialize(step.tool().args());
                    String toolCallKey = step.tool().name() + "\n"
                            + serialize(canonicalize(step.tool().args()));
                    AiAssistantToolResult cachedResult = toolResultCache.get(toolCallKey);
                    if (cachedResult != null) {
                        noProgressSteps++;
                        if (noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                            return AiGenerationTaskResult.failed("no_progress");
                        }
                        toolTurns.add(new ToolTurn(
                                stepNumber, step.tool().name(), cachedResult));
                        continue;
                    }
                    int toolCallId = persistenceService.proposeTool(
                            turn, stepNumber, step.tool().name(), argumentsJson);
                    publish(turn, new AiChatStepFrameDto(
                            turn.workspaceId(), turn.sessionId(), turn.turnId(),
                            stepNumber, "step", step.tool().name(),
                            "proposed", null));
                    try {
                        requireCurrentToolExecution(turn);
                        AiAssistantToolResult toolResult = toolExecutor.execute(
                                step.tool().name(), step.tool().args(), resources,
                                turn.includePrivateNotes());
                        String resultJson = promptAssembler.durableToolResult(toolResult);
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
                        if (seenToolResults.add(resultJson)) {
                            noProgressSteps = 0;
                        } else {
                            noProgressSteps++;
                        }
                        toolTurns.add(new ToolTurn(stepNumber, step.tool().name(), toolResult));
                        if (noProgressSteps >= MAX_CONSECUTIVE_NO_PROGRESS_STEPS) {
                            return AiGenerationTaskResult.failed("no_progress");
                        }
                    } catch (AiAssistantLoopException exception) {
                        failTool(turn, toolCallId, exception.detailReason());
                        publish(turn, new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "failed", exception.detailReason()));
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
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                resources.requireKnownCitations(finalAnswer.citations());
                String metadata = promptAssembler.finalMetadata(
                        finalAnswer.citations(), resources.snapshot());
                requireCurrentAccess(turn);
                persistenceService.resolve(
                        turn, finalAnswer.text(), metadata, inputTokens, outputTokens);
                return AiGenerationTaskResult.resolved(
                        new AiChatTurnGenerationResult(turn.turnId(), "resolved"));
            }
            return AiGenerationTaskResult.failed(
                    maxSteps == HARD_MAX_STEPS
                            ? "agent_backstop_exceeded"
                            : "step_cap_exceeded");
        } catch (AiAssistantLoopException exception) {
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

    private boolean restrictionsChanged(AiChatQueuedTurn turn) {
        return restrictionEpoch.current(turn.workspaceId()) != turn.restrictionEpoch();
    }

    private void requireCurrentAccess(AiChatQueuedTurn turn) {
        requireWorkspaceEnabled(turn);
        if (restrictionsChanged(turn)) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
        try {
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
        realtimeDispatcher.sessionNow(turn.workspaceId(), turn.sessionId(), frame);
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

    static List<AiChatMessage> boundedHistory(
            List<AiChatMessage> history, AiChatQueuedTurn turn) {
        AiChatMessage initiatingMessage = history.stream()
                .filter(message -> message.getId() == turn.userMessageId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Assistant initiating message is unavailable"));
        int remaining = Math.max(
                0, MAX_HISTORY_CHARS - initiatingMessage.getContent().length());
        List<AiChatMessage> selected = new ArrayList<>();
        for (int index = history.size() - 1; index >= 0; index--) {
            AiChatMessage message = history.get(index);
            if (message.getId() == turn.userMessageId()) {
                selected.add(message);
                continue;
            }
            if (remaining == 0) {
                continue;
            }
            String content = message.getContent();
            if (content.length() <= remaining) {
                selected.add(message);
                remaining -= content.length();
                continue;
            }
            selected.add(copyWithContent(
                    message, content.substring(content.length() - remaining)));
            remaining = 0;
        }
        java.util.Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private static AiChatMessage copyWithContent(AiChatMessage source, String content) {
        AiChatMessage copy = new AiChatMessage();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setSessionId(source.getSessionId());
        copy.setSeq(source.getSeq());
        copy.setAuthorKind(source.getAuthorKind());
        copy.setAuthorUserId(source.getAuthorUserId());
        copy.setContent(content);
        copy.setStructuredJson(source.getStructuredJson());
        copy.setInputTokens(source.getInputTokens());
        copy.setOutputTokens(source.getOutputTokens());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }
}
