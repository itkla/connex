package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.beans.AiChatMessage;

/** Whole-message prompt history plus provider-aware budgets and compaction token usage. */
public record AiChatMemory(
        List<AiChatMessage> history,
        AiAssistantPromptBudget budget,
        int inputTokens,
        int outputTokens) {

    public AiChatMemory {
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        Objects.requireNonNull(budget, "budget");
    }
}
