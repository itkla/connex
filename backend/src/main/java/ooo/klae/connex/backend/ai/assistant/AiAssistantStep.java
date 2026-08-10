package ooo.klae.connex.backend.ai.assistant;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** Strict structured result for one assistant model step. */
public record AiAssistantStep(Tool tool, @JsonProperty("final") FinalAnswer finalAnswer) {
    /** Proposed read-tool call. */
    public record Tool(String name, JsonNode args) {
    }

    /** Proposed terminal answer grounded by per-turn resource handles. */
    public record FinalAnswer(String text, List<String> citations) {
        public FinalAnswer {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
