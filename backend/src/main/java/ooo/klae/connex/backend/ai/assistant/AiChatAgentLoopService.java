package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler.ToolTurn;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.notifications.AiChatRealtimePublisher;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Executes the bounded masked assistant loop and commits only authorized durable outcomes. */
@Service
@RequiredArgsConstructor
public class AiChatAgentLoopService {
    static final int MAX_STEPS = 6;
    private static final String INTERNAL_ERROR = "internal_error";
    private static final int MAX_HISTORY_MESSAGES = 50;
    private static final int MAX_HISTORY_CHARS = 64_000;
    private static final int MAX_OUTPUT_TOKENS = 1200;
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final double TEMPERATURE = 0.1;

    private final AiInvocationService invocationService;
    private final AiAssistantStepGuard stepGuard;
    private final AiAssistantToolCatalog toolCatalog;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantWriteToolService writeToolService;
    private final AiAssistantIdentifierResolver identifierResolver;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiRestrictionEpoch restrictionEpoch;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AiChatRealtimePublisher> realtimePublisher;

    /** Runs one committed turn under the shared generation context. */
    public AiGenerationTaskResult<AiChatTurnGenerationResult> run(AiChatQueuedTurn turn) {
        try {
            boolean running;
            try {
                running = persistenceService.markRunning(turn);
            } catch (ForbiddenException exception) {
                return AiGenerationTaskResult.failed("access_revoked");
            }
            if (!running) {
                return AiGenerationTaskResult.failed(INTERNAL_ERROR);
            }
            publish(turn.userId(), new AiChatStepFrameDto(
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
            int inputTokens = 0;
            int outputTokens = 0;

            for (int stepNumber = 1; stepNumber <= MAX_STEPS; stepNumber++) {
                AiInvocation invocation = new AiInvocation(
                        AiFeature.ASSISTANT_CHAT,
                        maskingContext,
                        promptAssembler.assemble(
                                history, pageContext, toolTurns, maskingContext, resources),
                        MAX_OUTPUT_TOKENS,
                        TEMPERATURE);
                AiStructuredOutcome<AiAssistantStep> outcome = invocationService.completeStructured(
                        invocation, AiAssistantStep.class, stepGuard);
                inputTokens = addTokens(inputTokens, inputTokens(outcome));
                outputTokens = addTokens(outputTokens, outputTokens(outcome));
                if (outcome instanceof AiStructuredOutcome.Malformed<?>) {
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                if (!(outcome instanceof AiStructuredOutcome.Parsed<?> parsed)
                        || !(parsed.value() instanceof AiAssistantStep step)) {
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                if (parsed.demaskWarnings() != 0) {
                    return AiGenerationTaskResult.failed("malformed_output");
                }
                if (step.tool() != null) {
                    requireCurrentAccess(turn);
                    toolExecutor.validateReferences(
                            step.tool().name(), step.tool().args(), resources);
                    if (toolCatalog.isWrite(step.tool().name())) {
                        AiAssistantPreparedWrite write = writeToolService.prepare(
                                step.tool().name(), step.tool().args(), resources);
                        AiAssistantToolProposal proposal =
                                persistenceService.proposeWriteTool(turn, write);
                        int toolCallId = proposal.id();
                        publish(turn.userId(), new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "proposed", null, toolCallId));
                        try {
                            requireCurrentToolExecution(turn);
                            AiAssistantToolResult toolResult = write.tier()
                                    == AiAssistantToolCatalog.ToolTier.AUTO
                                    ? writeToolService.executeAuto(turn, toolCallId).toolResult()
                                    : writeToolService.proposalResult(write, proposal);
                            String status = write.tier() == AiAssistantToolCatalog.ToolTier.AUTO
                                    ? "executed"
                                    : ("executed".equals(proposal.status())
                                            ? "executed"
                                            : "approval_required");
                            publish(turn.userId(), new AiChatStepFrameDto(
                                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                    stepNumber, "step", step.tool().name(),
                                    status, null, toolCallId));
                            toolTurns.add(new ToolTurn(
                                    stepNumber, step.tool().name(), toolResult));
                        } catch (AiAssistantLoopException exception) {
                            failTool(turn, toolCallId, exception.detailReason());
                            publish(turn.userId(), new AiChatStepFrameDto(
                                    turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                    stepNumber, "step", step.tool().name(),
                                    "failed", exception.detailReason(), toolCallId));
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
                    String argumentsJson = serialize(step.tool().args());
                    int toolCallId = persistenceService.proposeTool(
                            turn, stepNumber, step.tool().name(), argumentsJson);
                    publish(turn.userId(), new AiChatStepFrameDto(
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
                        publish(turn.userId(), new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "executed", null));
                        toolTurns.add(new ToolTurn(stepNumber, step.tool().name(), toolResult));
                    } catch (AiAssistantLoopException exception) {
                        failTool(turn, toolCallId, exception.detailReason());
                        publish(turn.userId(), new AiChatStepFrameDto(
                                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                                stepNumber, "step", step.tool().name(),
                                "failed", exception.detailReason()));
                        return AiGenerationTaskResult.failed(exception.terminalReason());
                    } catch (RuntimeException exception) {
                        String reason = toolFailureReason(exception);
                        failTool(turn, toolCallId, reason);
                        publish(turn.userId(), new AiChatStepFrameDto(
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
            return AiGenerationTaskResult.failed("step_cap_exceeded");
        } catch (AiAssistantLoopException exception) {
            return AiGenerationTaskResult.failed(exception.terminalReason());
        } catch (TooManyRequestsException exception) {
            return AiGenerationTaskResult.failed("quota_exhausted");
        } catch (AiProviderException exception) {
            return AiGenerationTaskResult.failed("provider_error");
        } catch (ResourceNotFoundException exception) {
            return AiGenerationTaskResult.failed("access_revoked");
        } catch (ForbiddenException exception) {
            if (restrictionsChanged(turn)) {
                return AiGenerationTaskResult.failed("restrictions_changed");
            }
            return AiGenerationTaskResult.failed("access_revoked");
        } catch (RuntimeException exception) {
            if (restrictionsChanged(turn)) {
                return AiGenerationTaskResult.failed("restrictions_changed");
            }
            if (Thread.currentThread().isInterrupted()) {
                return AiGenerationTaskResult.timedOut("generation_timeout");
            }
            return AiGenerationTaskResult.failed(INTERNAL_ERROR);
        }
    }

    private boolean restrictionsChanged(AiChatQueuedTurn turn) {
        return restrictionEpoch.current(turn.workspaceId()) != turn.restrictionEpoch();
    }

    private void requireCurrentAccess(AiChatQueuedTurn turn) {
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

    private void publish(int userId, AiChatStepFrameDto frame) {
        try {
            AiChatRealtimePublisher publisher = realtimePublisher.getIfAvailable();
            if (publisher != null) {
                publisher.send(userId, frame);
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    private void failTool(AiChatQueuedTurn turn, int toolCallId, String reason) {
        persistenceService.failTool(
                turn, toolCallId, serialize(Map.of("reason", reason)));
    }

    private static String toolFailureReason(RuntimeException exception) {
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
