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
    public record FinalAnswer(
            String text,
            List<String> citations,
            List<String> suggestions,
            String title) {

        /** Creates a terminal answer without follow-up suggestions or a generated title. */
        public FinalAnswer(String text, List<String> citations) {
            this(text, citations, List.of(), null);
        }

        public FinalAnswer {
            citations = citations == null ? List.of() : List.copyOf(citations);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }
}
