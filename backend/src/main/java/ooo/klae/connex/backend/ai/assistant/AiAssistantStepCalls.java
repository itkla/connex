package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import tools.jackson.databind.JsonNode;

/**
 * The tool calls one model step produced, paired with the provider calls that carried them.
 *
 * <p>Loop-local and deliberately list-shaped so the two protocols meet at one point: the JSON ReAct
 * step object is structurally single-call and hands the loop a list of one, and a native response
 * hands it one call, or — on an endpoint an operator declared for batches — up to
 * {@link AiProviderCapabilities#MAX_PARALLEL_TOOL_CALLS}. The loop keeps a single execution path
 * instead of one per protocol.
 *
 * <p>A step's only call carries the sole-call ordinal; the calls of a batch carry their positions
 * {@code 1..K} in the order the model emitted them. The replay and the durable key both read that
 * ordinal, so a batch is never mistaken for K single-call steps.
 *
 * <p>The provider call is absent on the JSON ReAct path, where there is no provider-assigned call
 * identity to correlate a replayed exchange with, and present on every native path.
 *
 * @param calls the step's calls in the order the model emitted them, never empty
 */
record AiAssistantStepCalls(List<Call> calls) {

    AiAssistantStepCalls {
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("Assistant step tool calls are required");
        }
    }

    /**
     * Returns the one call a JSON ReAct step or an unbatched native step produced.
     *
     * @param tool the demasked tool the step proposed
     * @param providerCall the native call that carried it, empty on the JSON ReAct path
     * @return the step's calls as a list of one
     */
    static AiAssistantStepCalls of(AiAssistantStep.Tool tool, Optional<AiToolCall> providerCall) {
        return new AiAssistantStepCalls(
                List.of(new Call(AiAssistantToolCallRef.SOLE_CALL, tool, providerCall)));
    }

    /**
     * Returns the calls one validated native response carried.
     *
     * <p>One call keeps the sole-call ordinal, so an undeclared endpoint's step is exactly what it
     * always was; several are numbered {@code 1..K} in provider order.
     *
     * @param providerCalls the response's calls in the order the provider emitted them
     * @param arguments each call's demasked arguments object, in the same order
     * @return the step's calls
     */
    static AiAssistantStepCalls ofNative(List<AiToolCall> providerCalls, List<JsonNode> arguments) {
        Objects.requireNonNull(providerCalls, "providerCalls");
        Objects.requireNonNull(arguments, "arguments");
        if (providerCalls.isEmpty() || providerCalls.size() != arguments.size()) {
            throw new IllegalArgumentException(
                    "Assistant native step calls and arguments must correspond");
        }
        if (providerCalls.size() == 1) {
            AiToolCall call = providerCalls.getFirst();
            return of(
                    new AiAssistantStep.Tool(call.name(), arguments.getFirst()),
                    Optional.of(call));
        }
        List<Call> calls = new ArrayList<>(providerCalls.size());
        for (int index = 0; index < providerCalls.size(); index++) {
            AiToolCall call = providerCalls.get(index);
            calls.add(new Call(
                    index + 1,
                    new AiAssistantStep.Tool(call.name(), arguments.get(index)),
                    Optional.of(call)));
        }
        return new AiAssistantStepCalls(calls);
    }

    /** @return whether this step carries more than one call */
    boolean batched() {
        return calls.size() > 1;
    }

    /**
     * One proposed tool call of a model step and the provider call that carried it.
     *
     * @param ordinal the call's position in its step, or 0 when it is the step's only call
     * @param tool the demasked tool name and arguments the step proposed
     * @param providerCall the native call, empty on the JSON ReAct path
     */
    record Call(int ordinal, AiAssistantStep.Tool tool, Optional<AiToolCall> providerCall) {

        Call {
            if (ordinal < 0 || ordinal > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
                throw new IllegalArgumentException("Assistant step call ordinal is invalid");
            }
            Objects.requireNonNull(tool, "tool");
            providerCall = Objects.requireNonNull(providerCall, "providerCall");
        }

        /** @return opaque provider replay state for this call, or null when it carries none */
        String thoughtSignature() {
            return providerCall.map(AiToolCall::thoughtSignature).orElse(null);
        }
    }
}
