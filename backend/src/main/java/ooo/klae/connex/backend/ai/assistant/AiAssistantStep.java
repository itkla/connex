package ooo.klae.connex.backend.ai.assistant;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
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
            String title,
            List<AnswerBlock> blocks,
            Coverage coverage) {

        /** Creates a terminal answer without follow-up suggestions or a generated title. */
        public FinalAnswer(String text, List<String> citations) {
            this(text, citations, List.of(), null, null, null);
        }

        /** Creates the historical prose-only answer shape for compatibility tests and replay. */
        public FinalAnswer(
                String text,
                List<String> citations,
                List<String> suggestions,
                String title) {
            this(text, citations, suggestions, title, null, null);
        }

        public FinalAnswer {
            citations = citations == null ? List.of() : List.copyOf(citations);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            blocks = blocks == null || blocks.isEmpty()
                    ? List.of(new AnswerBlock(
                            "answer", null, text, List.of(), List.of(), citations))
                    : List.copyOf(blocks);
            // A synthesized coverage is never evidence that the requested scope was fully checked,
            // so it must not be able to assert "complete". Only a provider-declared coverage that
            // survived AiAssistantStepGuard may claim complete coverage.
            coverage = coverage == null
                    ? new Coverage(
                            "insufficient", null, null, null, List.of(), List.of(), false)
                    : coverage;
        }
    }

    /**
     * One typed answer-document block whose evidence is expressed as resource handles.
     *
     * <p>Serialized verbatim into the durable {@code structured_json} answer document, which is
     * revalidated on read with exact-key-count checks. Property inclusion is pinned to ALWAYS so
     * that the application-wide {@code non_null} inclusion cannot drop a null title or body and
     * silently invalidate the stored document.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnswerBlock(
            String kind,
            String title,
            String body,
            List<String> items,
            List<Row> rows,
            List<String> citations) {

        public AnswerBlock {
            items = items == null ? List.of() : List.copyOf(items);
            rows = rows == null ? List.of() : List.copyOf(rows);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /**
     * One structured row inside a metric, comparison, timeline, diff, or extraction block.
     *
     * <p>Inclusion is pinned to ALWAYS for the same durable-document reason as {@link AnswerBlock}.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(
            String label,
            String value,
            String detail,
            String at,
            List<String> citations) {

        public Row {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /**
     * Bounded answer coverage, freshness, exclusions, and truncation disclosure.
     *
     * <p>Inclusion is pinned to ALWAYS for the same durable-document reason as {@link AnswerBlock};
     * {@code asOf}, {@code periodStart}, and {@code periodEnd} are null in most real answers.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Coverage(
            String status,
            String asOf,
            String periodStart,
            String periodEnd,
            List<String> sources,
            List<String> exclusions,
            boolean truncated) {

        public Coverage {
            sources = sources == null ? List.of() : List.copyOf(sources);
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        }
    }
}
