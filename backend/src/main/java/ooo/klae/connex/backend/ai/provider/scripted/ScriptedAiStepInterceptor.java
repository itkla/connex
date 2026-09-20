package ooo.klae.connex.backend.ai.provider.scripted;

/**
 * Hook invoked on the agent-loop thread after the cursor resolves and before the emission.
 *
 * <p>It exists for exactly one class of trajectory: those whose subject is what happens when turn
 * state changes <em>between</em> two model steps — a workspace restriction epoch advancing, access
 * being withdrawn. Production reaches those states from another thread mid-turn, and the next
 * step's egress wrapper refuses; a test that mutated the state before the turn started would
 * rehearse a different code path.
 *
 * <p>Zero implementations ship on the main classpath. The provider injects a list that is empty in
 * the running application, the interface hands out only a script id and a step index — no request,
 * no response, no ability to change what is emitted — and
 * {@code ScriptedAiProviderArchTest} fails the build if an implementation appears in
 * {@code src/main}.
 */
@FunctionalInterface
public interface ScriptedAiStepInterceptor {

    /**
     * Runs immediately before a scripted step is emitted.
     * @param scriptId the resolved script's fixture id
     * @param completedToolCalls tool calls the loop had completed when this step was selected
     */
    void beforeEmit(String scriptId, int completedToolCalls);
}
