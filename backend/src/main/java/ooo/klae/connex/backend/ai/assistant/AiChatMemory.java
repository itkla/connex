package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.beans.AiChatMessage;

/**
 * Whole-message prompt history plus provider-aware budgets and compaction token usage.
 *
 * @param history the provider-sized message history this turn is built from
 * @param budget the turn's derived prompt allocation
 * @param inputTokens prompt tokens this turn's compaction already spent
 * @param outputTokens generated tokens this turn's compaction already spent
 * @param nativeTools whether the configured provider answers under the native-tool protocol
 * @param parallelToolCalls how many calls one model step of this turn may carry, as the configured
 *     target declared it before the first step; an upper bound only, because every invocation
 *     re-resolves the organization's provider and refuses a request above what the target it is
 *     actually sent to declares
 */
public record AiChatMemory(
        List<AiChatMessage> history,
        AiAssistantPromptBudget budget,
        int inputTokens,
        int outputTokens,
        boolean nativeTools,
        int parallelToolCalls) {

    /** Creates memory for the unchanged JSON-ReAct provider path. */
    public AiChatMemory(
            List<AiChatMessage> history,
            AiAssistantPromptBudget budget,
            int inputTokens,
            int outputTokens) {
        this(history, budget, inputTokens, outputTokens, false);
    }

    /** Creates memory for a turn whose model steps carry at most one tool call. */
    public AiChatMemory(
            List<AiChatMessage> history,
            AiAssistantPromptBudget budget,
            int inputTokens,
            int outputTokens,
            boolean nativeTools) {
        this(history, budget, inputTokens, outputTokens, nativeTools, 1);
    }

    public AiChatMemory {
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        Objects.requireNonNull(budget, "budget");
        if (parallelToolCalls < 1
                || parallelToolCalls > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "Assistant parallel tool calls must be between 1 and "
                            + AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS);
        }
    }
}
