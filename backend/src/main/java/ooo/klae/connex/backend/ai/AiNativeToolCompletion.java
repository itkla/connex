package ooo.klae.connex.backend.ai;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Every validated, demasked native function call one model step produced.
     *
     * <p>Plural because a declared endpoint may answer one model decision with several calls, while
     * singular stays the overwhelming case. {@link #providerCall()} and {@link #arguments()} name
     * the sole call and refuse rather than guess when the response carried a batch, so no caller
     * can silently read the first of four and act as if that were the whole decision.
     *
     * @param providerCalls the step's calls in the order the provider emitted them, never empty
     * @param callArguments each call's demasked arguments object, one per call in the same order
     * @param demaskWarnings demask warnings summed across every call of the response
     * @param inputTokens provider-reported prompt tokens for the whole response
     * @param outputTokens provider-reported generated tokens for the whole response
     * @param stopReason provider-reported stop reason for the whole response
     * @param reasoning display-only reasoning returned beside the calls
     * @param narration model-authored narration returned beside the calls
     */
    record Tool<T>(
            List<AiToolCall> providerCalls,
            List<JsonNode> callArguments,
            int demaskWarnings,
            int inputTokens,
            int outputTokens,
            String stopReason,
            Optional<String> reasoning,
            Optional<String> narration) implements AiNativeToolCompletion<T> {

        /** Creates a single-call tool completion whose provider emitted no narration beside it. */
        public Tool(
                AiToolCall providerCall,
                JsonNode arguments,
                int demaskWarnings,
                int inputTokens,
                int outputTokens,
                String stopReason,
                Optional<String> reasoning) {
            this(providerCall, arguments, demaskWarnings, inputTokens, outputTokens,
                    stopReason, reasoning, Optional.empty());
        }

        /** Creates a single-call tool completion, the shape every undeclared endpoint produces. */
        public Tool(
                AiToolCall providerCall,
                JsonNode arguments,
                int demaskWarnings,
                int inputTokens,
                int outputTokens,
                String stopReason,
                Optional<String> reasoning,
                Optional<String> narration) {
            this(
                    List.of(Objects.requireNonNull(providerCall, "providerCall")),
                    List.of(Objects.requireNonNull(arguments, "arguments")),
                    demaskWarnings,
                    inputTokens,
                    outputTokens,
                    stopReason,
                    reasoning,
                    narration);
        }

        public Tool {
            providerCalls = List.copyOf(Objects.requireNonNull(providerCalls, "providerCalls"));
            List<JsonNode> declared =
                    List.copyOf(Objects.requireNonNull(callArguments, "callArguments"));
            if (providerCalls.isEmpty() || providerCalls.size() != declared.size()) {
                throw new IllegalArgumentException(
                        "AI native tool calls and arguments must correspond");
            }
            callArguments = List.copyOf(deepCopies(declared));
            Objects.requireNonNull(stopReason, "stopReason");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
            narration = Objects.requireNonNull(narration, "narration");
        }

        /**
         * Returns the one call a single-call response carried.
         *
         * @return the sole provider call
         * @throws IllegalStateException when the response carried more than one call
         */
        public AiToolCall providerCall() {
            requireSoleCall();
            return providerCalls.getFirst();
        }

        /**
         * Returns the demasked arguments a single-call response carried.
         *
         * @return a defensive copy of the sole call's arguments
         * @throws IllegalStateException when the response carried more than one call
         */
        public JsonNode arguments() {
            requireSoleCall();
            return callArguments.getFirst().deepCopy();
        }

        public List<JsonNode> callArguments() {
            return List.copyOf(deepCopies(callArguments));
        }

        private void requireSoleCall() {
            if (providerCalls.size() != 1) {
                throw new IllegalStateException(
                        "AI native tool response carried " + providerCalls.size() + " calls");
            }
        }

        private static List<JsonNode> deepCopies(List<JsonNode> arguments) {
            List<JsonNode> copies = new ArrayList<>(arguments.size());
            for (JsonNode node : arguments) {
                if (node == null || !node.isObject()) {
                    throw new IllegalArgumentException(
                            "AI native tool arguments must be an object");
                }
                copies.add(node.deepCopy());
            }
            return copies;
        }

        @Override
        public String toString() {
            return "Tool[providerCalls=" + providerCalls
                    + ", callArguments=<redacted>"
                    + ", demaskWarnings=" + demaskWarnings
                    + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens
                    + ", stopReason=" + stopReason
                    + ", reasoning=<redacted>"
                    + ", narration=<redacted>]";
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

        @Override
        public String toString() {
            return "Content[attempt=" + attempt
                    + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens
                    + ", stopReason=" + stopReason
                    + ", reasoning=<redacted>]";
        }
    }

    /** Repairable rejection for a malformed native function-call envelope. */
    record Malformed<T>(
            int inputTokens,
            int outputTokens,
            String stopReason,
            Optional<String> reasoning,
            String repairRule) implements AiNativeToolCompletion<T> {

        public Malformed(
                int inputTokens,
                int outputTokens,
                String stopReason,
                Optional<String> reasoning) {
            this(inputTokens, outputTokens, stopReason, reasoning, "native_tool_call");
        }

        public Malformed {
            Objects.requireNonNull(stopReason, "stopReason");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
            Objects.requireNonNull(repairRule, "repairRule");
        }

        @Override
        public String toString() {
            return "Malformed[inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens
                    + ", stopReason=" + stopReason
                    + ", reasoning=<redacted>"
                    + ", repairRule=" + repairRule + "]";
        }
    }
}
