package ooo.klae.connex.backend.ai.assistant;

/**
 * Names one tool call within a turn by the model step it belongs to and its place in that step.
 *
 * <p>The correlation key the loop, the replay and the durable row all agree on. A bare step number
 * was enough only while a step could carry at most one call; naming the position too keeps the
 * recorded provider call, the replayed exchange and the {@code ai_chat_tool_call} row talking about
 * the same call rather than about the same decision.
 *
 * <p>{@code callOrdinal == 0} means <em>the sole call of its step</em> and renders no key suffix at
 * all, so every write, every {@code find_tools}, every unbatched read and every server-side skill
 * plan step keeps the exact durable key it has always had. Ordinals from 1 upwards are positions in
 * the order the model emitted its calls.
 *
 * @param stepNumber the durable model-step number, from 1
 * @param callOrdinal the call's position in its step, or 0 when it is the step's only call
 */
public record AiAssistantToolCallRef(int stepNumber, int callOrdinal) {

    public AiAssistantToolCallRef {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException("Assistant tool call step must be positive");
        }
        if (callOrdinal < 0) {
            throw new IllegalArgumentException("Assistant tool call ordinal must not be negative");
        }
    }

    /**
     * Returns the reference naming the sole call of one model step.
     *
     * @param stepNumber the durable model-step number
     * @return the reference whose key renders exactly as it did before call ordinals existed
     */
    public static AiAssistantToolCallRef soleCall(int stepNumber) {
        return new AiAssistantToolCallRef(stepNumber, 0);
    }

    /**
     * Renders the durable idempotency-key suffix this call owns.
     *
     * @return the empty string for the sole call of a step, and {@code -call-k} otherwise
     */
    public String keySuffix() {
        return callOrdinal == 0 ? "" : "-call-" + callOrdinal;
    }
}
