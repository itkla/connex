package ooo.klae.connex.backend.ai.provider.scripted;

import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;

/**
 * Declared capability shape for one scripted model id.
 *
 * <p>The scripted adapter answers under the {@code openai_compatible} provider id, so a configured
 * model id is the only dimension a trajectory can vary. An unknown model id is refused rather than
 * defaulted: a silent fallback would let a mistyped fixture rehearse a protocol nobody chose.
 *
 * <p><b>Fidelity scope for {@link #JSON}.</b> The shadowed {@code OpenAiCompatibleAdapter} returns
 * {@code JSON_SCHEMA} and {@code NATIVE_FUNCTIONS} unconditionally, so a real
 * {@code openai_compatible} target is always native. {@code scripted-json} therefore rehearses the
 * JSON protocol as other provider families and the runtime native-to-JSON degradation path produce
 * it, not as {@code openai_compatible} ever presents it.
 */
public enum ScriptedAiCapabilityClass {

    /** Buffered native function calling with a structured terminal answer. */
    NATIVE(
            "scripted-native",
            AiStructuredOutputEnforcement.JSON_SCHEMA,
            AiToolCallingMode.NATIVE_FUNCTIONS,
            AiReasoningMode.TAGGED,
            false,
            200_000,
            16_384),

    /** Streamed native function calling, so a trajectory can drive ordered content deltas. */
    NATIVE_STREAM(
            "scripted-native-stream",
            AiStructuredOutputEnforcement.JSON_SCHEMA,
            AiToolCallingMode.NATIVE_FUNCTIONS,
            AiReasoningMode.TAGGED,
            true,
            200_000,
            16_384),

    /** Prompt-only JSON protocol with no native tools. */
    JSON(
            "scripted-json",
            AiStructuredOutputEnforcement.PROMPT_ONLY,
            AiToolCallingMode.NONE,
            AiReasoningMode.TAGGED,
            false,
            200_000,
            16_384),

    /** A window below the assistant context floor, so the floor refusal can be rehearsed. */
    SMALL_CONTEXT(
            "scripted-small-context",
            AiStructuredOutputEnforcement.PROMPT_ONLY,
            AiToolCallingMode.NONE,
            AiReasoningMode.TAGGED,
            false,
            32_768,
            4_096);

    private final String modelId;
    private final AiStructuredOutputEnforcement structuredOutput;
    private final AiToolCallingMode toolCalling;
    private final AiReasoningMode reasoning;
    private final boolean streaming;
    private final int contextWindowTokens;
    private final int maxOutputTokens;

    ScriptedAiCapabilityClass(
            String modelId,
            AiStructuredOutputEnforcement structuredOutput,
            AiToolCallingMode toolCalling,
            AiReasoningMode reasoning,
            boolean streaming,
            int contextWindowTokens,
            int maxOutputTokens) {
        this.modelId = modelId;
        this.structuredOutput = structuredOutput;
        this.toolCalling = toolCalling;
        this.reasoning = reasoning;
        this.streaming = streaming;
        this.contextWindowTokens = contextWindowTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** @return configured provider model id this capability class answers for */
    public String modelId() {
        return modelId;
    }

    /** @return structured-output enforcement this class declares */
    public AiStructuredOutputEnforcement structuredOutput() {
        return structuredOutput;
    }

    /** @return function-tool protocol this class declares */
    public AiToolCallingMode toolCalling() {
        return toolCalling;
    }

    /** @return reasoning protocol this class declares */
    public AiReasoningMode reasoning() {
        return reasoning;
    }

    /** @return whether this class accepts a streamed completion */
    public boolean streaming() {
        return streaming;
    }

    /** @return declared context window in tokens */
    public int contextWindowTokens() {
        return contextWindowTokens;
    }

    /** @return declared maximum generated output in tokens */
    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * Resolves a configured model id to its declared capability class.
     * @param modelId configured provider model id
     * @return declared capability class
     * @throws AiProviderException when the model id is not a scripted capability class
     */
    public static ScriptedAiCapabilityClass forModelId(String modelId) {
        for (ScriptedAiCapabilityClass candidate : values()) {
            if (candidate.modelId.equals(modelId)) {
                return candidate;
            }
        }
        throw new AiProviderException("Unknown scripted AI capability class");
    }

    /**
     * Whether a configured model id names a scripted capability class.
     * @param modelId configured provider model id
     * @return whether the model id is declared here
     */
    public static boolean isDeclared(String modelId) {
        for (ScriptedAiCapabilityClass candidate : values()) {
            if (candidate.modelId.equals(modelId)) {
                return true;
            }
        }
        return false;
    }
}
