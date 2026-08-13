package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.masking.AiGeneratedContentScreen;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Incrementally compacts assistant transcript history without splitting message content. */
@Service
@RequiredArgsConstructor
public class AiChatMemoryService {
    private static final int MAX_RECENT_MESSAGES = 100;
    private static final int MAX_COMPACTION_MESSAGES = 500;
    private static final int MAX_VERBATIM_MESSAGES = 12;
    private static final int COMPACTION_THRESHOLD_PERCENT = 80;
    private static final int VERBATIM_BUDGET_PERCENT = 60;
    private static final int SUMMARY_MAX_OUTPUT_TOKENS = 4_096;
    private static final double SUMMARY_TEMPERATURE = 0.1;
    private static final String OVERSIZED_MESSAGE_OMISSION =
            "Historical message omitted because it exceeded the compaction input budget.";

    private final AiInvocationService invocationService;
    private final AiInvocationAdmissionService invocationAdmissionService;
    private final AiProperties aiProperties;
    private final AiAssistantIdentifierResolver identifierResolver;
    private final AiAssistantPromptAssembler promptAssembler;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantSummaryGuard summaryGuard;
    private final AiAssistantSummarySchema summarySchema;
    private final AiAssistantStepSchema stepSchema;
    private final AiChatTurnPersistenceService persistenceService;
    private final AiWorkspaceGovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Prepares current provider-sized history, compacting the oldest whole messages before the
     * supplied turn deadline.
     */
    public AiChatMemory prepare(
            AiChatQueuedTurn turn,
            MaskingContext context,
            Instant deadline) {
        requireBeforeDeadline(deadline);
        var capabilities = invocationService.currentProviderCapabilities(
                AiFeature.ASSISTANT_CHAT);
        boolean nativeTools = capabilities.toolCalling() == AiToolCallingMode.NATIVE_FUNCTIONS;
        AiReasoningMode reasoningMode = aiProperties.isAssistantThinkingEnabled()
                ? nativeTools
                        ? capabilities.nativeToolReasoning()
                        : capabilities.reasoning()
                : AiReasoningMode.NONE;
        int fixedEnvelopeBytes = nativeTools
                ? invocationService.serializedPromptBytes(
                        promptAssembler.fixedNativePrompt(),
                        stepSchema.finalResponseSchema(),
                        reasoningMode,
                        new AiNativeToolRequest(
                                promptAssembler.nativeToolDefinitions(), List.of()))
                : invocationService.serializedPromptBytes(
                        promptAssembler.fixedPrompt(),
                        stepSchema.responseSchema(),
                        reasoningMode);
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                capabilities,
                aiProperties.getAssistantMaxOutputTokens(),
                fixedEnvelopeBytes);
        List<AiChatMessage> recent = persistenceService.loadHistory(
                turn, MAX_RECENT_MESSAGES);
        AiChatMessage initiatingMessage = recent.stream()
                .filter(message -> message.getId() == turn.userMessageId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Assistant initiating message is unavailable"));
        if (utf8Bytes(initiatingMessage.getContent()) > budget.historyBytes()) {
            throw new AiAssistantLoopException(
                    "prompt_budget_exceeded", "prompt_budget_exceeded");
        }
        identifierResolver.seed(initiatingMessage.getContent(), context);
        AiChatMessage summary = persistenceService.loadHistorySummary(turn);
        if (summary != null
                && saturatedAdd(
                        utf8Bytes(summary.getContent()), utf8Bytes(initiatingMessage.getContent()))
                        > budget.historyBytes()) {
            throw new AiAssistantLoopException(
                    "prompt_budget_exceeded", "prompt_budget_exceeded");
        }
        int summaryThroughSeq = summaryThroughSeq(summary);
        List<AiChatMessage> dialogue = dialogueAfter(recent, summaryThroughSeq);
        int inputTokens = 0;
        int outputTokens = 0;
        if (shouldCompact(summary, dialogue, budget)) {
            while (true) {
                requireBeforeDeadline(deadline);
                int verbatimBudget = summary == null
                        ? budget.historyBytes() * VERBATIM_BUDGET_PERCENT / 100
                        : budget.historyBytes() - utf8Bytes(summary.getContent());
                List<AiChatMessage> verbatim = newestWholeMessages(
                        dialogue,
                        Math.max(initiatingMessage.getContent().length(), verbatimBudget),
                        initiatingMessage);
                int beforeSeq = verbatim.getFirst().getSeq();
                List<AiChatMessage> candidates = persistenceService.loadCompactionCandidates(
                        turn, summaryThroughSeq, beforeSeq, MAX_COMPACTION_MESSAGES);
                if (candidates.isEmpty()) {
                    break;
                }
                List<AiChatMessage> source = oldestWholeMessages(
                        candidates, budget.compactionSourceBytes());
                if (source.isEmpty()) {
                    source = List.of(oversizedCompactionMessage(candidates.getFirst()));
                }
                identifierResolver.seed(identifierSource(summary, source), context);
                List<AiChatMessage> provenance = new ArrayList<>(source.size() + 1);
                if (summary != null) {
                    provenance.add(summary);
                }
                provenance.addAll(source);
                AiChatResourceRegistry summaryResources = new AiChatResourceRegistry();
                toolExecutor.pageContext(
                        promptAssembler.replayPageContext(provenance), summaryResources);
                AiInvocation invocation = new AiInvocation(
                        AiFeature.ASSISTANT_CHAT,
                        context,
                        promptAssembler.assembleSummary(
                                summary, source, context, summaryResources),
                        Math.min(SUMMARY_MAX_OUTPUT_TOKENS, budget.maxOutputTokens()),
                        SUMMARY_TEMPERATURE,
                        false,
                        deadline);
                AiStructuredOutcome<AiAssistantSummary> outcome;
                try (AiInvocationAdmissionService.DirectAdmission admission =
                        invocationAdmissionService.acquireDirect()) {
                    outcome = invocationService.completeStructuredRepairable(
                            invocation,
                            AiAssistantSummary.class,
                            summaryGuard,
                            summarySchema.responseSchema(),
                            admission,
                            () -> {
                                requireBeforeDeadline(deadline);
                                if (!governanceService.isEnabled(turn.workspaceId())) {
                                    throw new AiAssistantLoopException(
                                            "workspace_disabled", "workspace_disabled");
                                }
                                persistenceService.requireRunning(turn);
                                requireBeforeDeadline(deadline);
                            }).outcome();
                }
                requireBeforeDeadline(deadline);
                if (!(outcome instanceof AiStructuredOutcome.Parsed<?> parsed)
                        || !(parsed.value() instanceof AiAssistantSummary parsedSummary)
                        || parsed.demaskWarnings() != 0) {
                    throw new AiAssistantLoopException(
                            "summary_compaction_failed", "summary_compaction_failed");
                }
                String generated = parsedSummary.summary().strip();
                if (generated.isBlank()
                        || AiGeneratedContentScreen.containsPlaceholder(generated)
                        || AiGeneratedContentScreen.rejectionReason(generated) != null) {
                    throw new AiAssistantLoopException(
                            "summary_compaction_failed", "summary_compaction_failed");
                }
                if (saturatedAdd(
                        utf8Bytes(generated), utf8Bytes(initiatingMessage.getContent()))
                        > budget.historyBytes()) {
                    throw new AiAssistantLoopException(
                            "summary_compaction_failed", "summary_compaction_failed");
                }
                int sourceFromSeq = summary == null
                        ? source.getFirst().getSeq()
                        : summarySourceFromSeq(summary);
                int throughSeq = source.getLast().getSeq();
                String metadata = summaryMetadata(
                        sourceFromSeq,
                        throughSeq,
                        summaryResources.snapshot(),
                        context,
                        generated);
                requireBeforeDeadline(deadline);
                summary = persistenceService.upsertHistorySummary(
                        turn,
                        summary == null ? null : summary.getId(),
                        summaryThroughSeq,
                        generated,
                        metadata,
                        parsed.inputTokens(),
                        parsed.outputTokens());
                summaryThroughSeq = throughSeq;
                inputTokens = saturatedAdd(inputTokens, parsed.inputTokens());
                outputTokens = saturatedAdd(outputTokens, parsed.outputTokens());
                dialogue = dialogueAfter(recent, summaryThroughSeq);
            }
        }
        if (summary != null
                && saturatedAdd(
                        utf8Bytes(summary.getContent()), utf8Bytes(initiatingMessage.getContent()))
                        > budget.historyBytes()) {
            throw new AiAssistantLoopException(
                    "prompt_budget_exceeded", "prompt_budget_exceeded");
        }
        List<AiChatMessage> bounded = boundedHistory(
                summary, dialogue, initiatingMessage, budget.historyBytes());
        identifierResolver.seed(identifierSource(null, bounded), context);
        return new AiChatMemory(
                bounded,
                budget,
                inputTokens,
                outputTokens,
                nativeTools);
    }

    static List<AiChatMessage> boundedHistory(
            AiChatMessage summary,
            List<AiChatMessage> dialogue,
            AiChatMessage initiatingMessage,
            int budgetBytes) {
        int remaining = Math.max(
                0, budgetBytes - (summary == null ? 0 : utf8Bytes(summary.getContent())));
        List<AiChatMessage> selected = new ArrayList<>();
        for (int index = dialogue.size() - 1; index >= 0; index--) {
            AiChatMessage message = dialogue.get(index);
            if (message.getId() == initiatingMessage.getId()) {
                selected.add(message);
                remaining = Math.max(0, remaining - utf8Bytes(message.getContent()));
            } else if (utf8Bytes(message.getContent()) <= remaining) {
                selected.add(message);
                remaining -= utf8Bytes(message.getContent());
            } else {
                break;
            }
        }
        Collections.reverse(selected);
        if (summary == null) {
            return List.copyOf(selected);
        }
        List<AiChatMessage> withSummary = new ArrayList<>(selected.size() + 1);
        withSummary.add(summary);
        withSummary.addAll(selected);
        return List.copyOf(withSummary);
    }

    private static boolean shouldCompact(
            AiChatMessage summary,
            List<AiChatMessage> dialogue,
            AiAssistantPromptBudget budget) {
        int bytes = summary == null ? 0 : utf8Bytes(summary.getContent());
        for (AiChatMessage message : dialogue) {
            bytes = saturatedAdd(bytes, utf8Bytes(message.getContent()));
        }
        return bytes >= budget.historyBytes() * COMPACTION_THRESHOLD_PERCENT / 100
                || dialogue.size() >= MAX_RECENT_MESSAGES;
    }

    private static List<AiChatMessage> newestWholeMessages(
            List<AiChatMessage> messages,
            int budgetBytes,
            AiChatMessage initiatingMessage) {
        List<AiChatMessage> selected = new ArrayList<>();
        int remaining = budgetBytes;
        for (int index = messages.size() - 1;
                index >= 0 && selected.size() < MAX_VERBATIM_MESSAGES;
                index--) {
            AiChatMessage message = messages.get(index);
            if (message.getId() == initiatingMessage.getId()
                    || utf8Bytes(message.getContent()) <= remaining) {
                selected.add(message);
                remaining = Math.max(0, remaining - utf8Bytes(message.getContent()));
            } else {
                break;
            }
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private static List<AiChatMessage> oldestWholeMessages(
            List<AiChatMessage> messages, int budgetBytes) {
        List<AiChatMessage> selected = new ArrayList<>();
        int remaining = budgetBytes;
        for (AiChatMessage message : messages) {
            if (utf8Bytes(message.getContent()) > remaining) {
                break;
            }
            selected.add(message);
            remaining -= utf8Bytes(message.getContent());
        }
        return List.copyOf(selected);
    }

    private int summaryThroughSeq(AiChatMessage summary) {
        return summary == null ? 0 : summaryMetadataInt(summary, "throughSeq");
    }

    private int summarySourceFromSeq(AiChatMessage summary) {
        return summaryMetadataInt(summary, "sourceFromSeq");
    }

    private int summaryMetadataInt(AiChatMessage summary, String field) {
        if (!"system".equals(summary.getAuthorKind()) || summary.getStructuredJson() == null) {
            throw new IllegalStateException("Assistant history summary metadata is invalid");
        }
        try {
            JsonNode metadata = objectMapper.readTree(summary.getStructuredJson());
            JsonNode kind = metadata.get("kind");
            JsonNode value = metadata.get(field);
            if (kind == null || !kind.isString() || !"history_summary".equals(kind.asString())
                    || value == null || !value.isIntegralNumber()
                    || !value.canConvertToInt() || value.asInt() <= 0) {
                throw new IllegalStateException("Assistant history summary metadata is invalid");
            }
            return value.asInt();
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant history summary metadata is invalid", exception);
        }
    }

    private String summaryMetadata(
            int sourceFromSeq,
            int throughSeq,
            java.util.Map<String, AiChatResourceRegistry.ResourceRef> resources,
            MaskingContext context,
            String generatedSummary) {
        try {
            List<java.util.Map<String, Object>> storedResources = resources.entrySet().stream()
                    .map(entry -> java.util.Map.<String, Object>of(
                            "handle", entry.getKey(),
                            "kind", entry.getValue().kind(),
                            "id", entry.getValue().id()))
                    .toList();
            String maskedSummary = MaskingEngine.maskFreeText(generatedSummary, context);
            List<java.util.Map<String, String>> storedIdentifiers = context.tokenBindings().stream()
                    .filter(binding -> maskedSummary.contains(binding.getKey()))
                    .map(binding -> java.util.Map.of(
                            "kind", identifierKind(binding.getKey()),
                            "value", binding.getValue()))
                    .toList();
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("kind", "history_summary");
            metadata.put("sourceFromSeq", sourceFromSeq);
            metadata.put("throughSeq", throughSeq);
            metadata.put("resources", storedResources);
            metadata.put("identifiers", storedIdentifiers);
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant history summary metadata could not be serialized", exception);
        }
    }

    private static boolean isDialogue(AiChatMessage message) {
        return "user".equals(message.getAuthorKind())
                || "assistant".equals(message.getAuthorKind());
    }

    private static List<AiChatMessage> dialogueAfter(
            List<AiChatMessage> messages, int sequence) {
        return messages.stream()
                .filter(AiChatMemoryService::isDialogue)
                .filter(message -> message.getSeq() > sequence)
                .toList();
    }

    private static String identifierSource(
            AiChatMessage summary, List<AiChatMessage> messages) {
        StringBuilder source = new StringBuilder();
        if (summary != null) {
            source.append(summary.getContent()).append('\n');
        }
        for (AiChatMessage message : messages) {
            source.append(message.getContent()).append('\n');
        }
        return source.toString();
    }

    private static AiChatMessage oversizedCompactionMessage(AiChatMessage source) {
        AiChatMessage omitted = new AiChatMessage();
        omitted.setId(source.getId());
        omitted.setWorkspaceId(source.getWorkspaceId());
        omitted.setSessionId(source.getSessionId());
        omitted.setSeq(source.getSeq());
        omitted.setAuthorKind(source.getAuthorKind());
        omitted.setAuthorUserId(source.getAuthorUserId());
        omitted.setContent(OVERSIZED_MESSAGE_OMISSION);
        omitted.setStructuredJson(source.getStructuredJson());
        omitted.setInputTokens(source.getInputTokens());
        omitted.setOutputTokens(source.getOutputTokens());
        omitted.setCreatedAt(source.getCreatedAt());
        return omitted;
    }

    private static String identifierKind(String token) {
        if (token == null || token.length() < 3) {
            throw new IllegalStateException("Assistant summary identifier token is invalid");
        }
        return switch (token.charAt(2)) {
            case 'P' -> "person";
            case 'C' -> "company";
            case 'D' -> "deal";
            case 'E' -> "email";
            case 'H' -> "phone";
            default -> throw new IllegalStateException(
                    "Assistant summary identifier token is invalid");
        };
    }

    private void requireBeforeDeadline(Instant deadline) {
        if (!clock.instant().isBefore(deadline)) {
            throw AiAssistantLoopException.deadlineExceeded();
        }
    }

    private static int saturatedAdd(int left, int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
