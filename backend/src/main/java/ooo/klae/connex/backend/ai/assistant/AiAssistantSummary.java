package ooo.klae.connex.backend.ai.assistant;

import java.util.Objects;

/** Strict generated conversation summary returned by the model compaction call. */
public record AiAssistantSummary(String summary) {

    public AiAssistantSummary {
        Objects.requireNonNull(summary, "summary");
    }
}
