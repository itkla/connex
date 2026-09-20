package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ooo.klae.connex.backend.ai.provider.AiToolCall;

/**
 * The tool calls one model step produced, paired with the provider calls that carried them.
 *
 * <p>Loop-local and deliberately list-shaped so the two protocols meet at one point: the JSON ReAct
 * step object is structurally single-call and a native response is bounded to one call today, so
 * both hand the loop a list of one and the loop keeps a single execution path instead of one per
 * protocol.
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
        return new AiAssistantStepCalls(List.of(new Call(tool, providerCall)));
    }

    /**
     * One proposed tool call of a model step and the provider call that carried it.
     *
     * @param tool the demasked tool name and arguments the step proposed
     * @param providerCall the native call, empty on the JSON ReAct path
     */
    record Call(AiAssistantStep.Tool tool, Optional<AiToolCall> providerCall) {

        Call {
            Objects.requireNonNull(tool, "tool");
            providerCall = Objects.requireNonNull(providerCall, "providerCall");
        }

        /** @return opaque provider replay state for this call, or null when it carries none */
        String thoughtSignature() {
            return providerCall.map(AiToolCall::thoughtSignature).orElse(null);
        }
    }
}
