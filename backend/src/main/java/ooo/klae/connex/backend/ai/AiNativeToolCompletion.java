package ooo.klae.connex.backend.ai;

import java.util.Objects;
import java.util.Optional;

import ooo.klae.connex.backend.ai.provider.AiToolCall;
import tools.jackson.databind.JsonNode;

/** One validated native function call, structured final-content attempt, or malformed call. */
public sealed interface AiNativeToolCompletion<T> {
    int inputTokens();
    int outputTokens();
    String stopReason();
    Optional<String> reasoning();

    /** Validated, demasked native function call ready for the assistant's normal routing. */
    record Tool<T>(
            AiToolCall providerCall,
            JsonNode arguments,
            int demaskWarnings,
            int inputTokens,
            int outputTokens,
            String stopReason,
            Optional<String> reasoning) implements AiNativeToolCompletion<T> {

        public Tool {
            Objects.requireNonNull(providerCall, "providerCall");
            JsonNode source = Objects.requireNonNull(arguments, "arguments");
            if (!source.isObject()) {
                throw new IllegalArgumentException("AI native tool arguments must be an object");
            }
            arguments = source.deepCopy();
            Objects.requireNonNull(stopReason, "stopReason");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
        }

        @Override
        public JsonNode arguments() {
            return arguments.deepCopy();
        }
    }

    /** Structured final-content attempt retaining the existing bounded repair contract. */
    record Content<T>(
            AiStructuredRepairAttempt<T> attempt,
            int inputTokens,
            int outputTokens,
            String stopReason,
            Optional<String> reasoning) implements AiNativeToolCompletion<T> {

        public Content {
            Objects.requireNonNull(attempt, "attempt");
            Objects.requireNonNull(stopReason, "stopReason");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
        }
    }

    /** Honest terminal rejection for a malformed native function-call envelope. */
    record Malformed<T>(
            int inputTokens,
            int outputTokens,
            String stopReason,
            Optional<String> reasoning) implements AiNativeToolCompletion<T> {

        public Malformed {
            Objects.requireNonNull(stopReason, "stopReason");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
        }
    }
}
