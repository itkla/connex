package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Exact adapter capabilities for one configured provider target.
 *
 * @param structuredOutput structured-output enforcement the target accepts
 * @param reasoning reasoning protocol the target answers under
 * @param contextWindowTokens declared context window in tokens
 * @param maxOutputTokens declared maximum generated output in tokens
 * @param toolCalling function-tool protocol the target accepts
 * @param nativeToolReasoning reasoning protocol used alongside native function tools
 * @param streaming whether the target accepts a streamed completion request
 * @param parallelToolCalls how many function calls one model step of this target may carry, from 1
 */
public record AiProviderCapabilities(
        AiStructuredOutputEnforcement structuredOutput,
        AiReasoningMode reasoning,
        int contextWindowTokens,
        int maxOutputTokens,
        AiToolCallingMode toolCalling,
        AiReasoningMode nativeToolReasoning,
        boolean streaming,
        int parallelToolCalls) {

    /**
     * The most function calls one model step may ever carry, whatever an operator declares.
     *
     * <p>A declaration ceiling rather than a preference. Four is the smallest number that collapses
     * a record crawl — a record plus its activities, tasks and notes — into one model decision, and
     * it keeps a turn's worst-case durable tool-call rows at a number one progress projection query
     * can still read whole. Raising it is a constant change plus a fresh endpoint probe, never a
     * silent widening.
     */
    public static final int MAX_PARALLEL_TOOL_CALLS = 4;

    private static final int ESTIMATED_UTF8_BYTES_PER_TOKEN = 4;

    public AiProviderCapabilities {
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        Objects.requireNonNull(reasoning, "reasoning");
        Objects.requireNonNull(toolCalling, "toolCalling");
        Objects.requireNonNull(nativeToolReasoning, "nativeToolReasoning");
        if (contextWindowTokens < 4_096) {
            throw new IllegalArgumentException("AI context window must contain at least 4096 tokens");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException(
                    "AI maximum output tokens must fit within the context window");
        }
        if (parallelToolCalls < 1 || parallelToolCalls > MAX_PARALLEL_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "AI parallel tool calls must be between 1 and " + MAX_PARALLEL_TOOL_CALLS);
        }
    }

    /** Creates capabilities whose target carries at most one function call per model step. */
    public AiProviderCapabilities(
            AiStructuredOutputEnforcement structuredOutput,
            AiReasoningMode reasoning,
            int contextWindowTokens,
            int maxOutputTokens,
            AiToolCallingMode toolCalling,
            AiReasoningMode nativeToolReasoning,
            boolean streaming) {
        this(structuredOutput, reasoning, contextWindowTokens, maxOutputTokens,
                toolCalling, nativeToolReasoning, streaming, 1);
    }

    /** Creates capabilities with buffered provider delivery and a conservative output ceiling. */
    public AiProviderCapabilities(
            AiStructuredOutputEnforcement structuredOutput,
            AiReasoningMode reasoning,
            int contextWindowTokens,
            AiToolCallingMode toolCalling,
            AiReasoningMode nativeToolReasoning) {
        this(structuredOutput, reasoning, contextWindowTokens, 4_096, toolCalling,
                nativeToolReasoning, false);
    }

    /** Creates capabilities for an adapter without native function tools. */
    public AiProviderCapabilities(
            AiStructuredOutputEnforcement structuredOutput,
            AiReasoningMode reasoning,
            int contextWindowTokens,
            int maxOutputTokens) {
        this(
                structuredOutput,
                reasoning,
                contextWindowTokens,
                maxOutputTokens,
                AiToolCallingMode.NONE,
                reasoning,
                false);
    }

    /** Creates capabilities whose native-tool reasoning matches the regular provider mode. */
    public AiProviderCapabilities(
            AiStructuredOutputEnforcement structuredOutput,
            AiReasoningMode reasoning,
            int contextWindowTokens,
            int maxOutputTokens,
            AiToolCallingMode toolCalling) {
        this(structuredOutput, reasoning, contextWindowTokens, maxOutputTokens,
                toolCalling, reasoning, false);
    }

    /** Creates buffered capabilities with an explicit native-tool reasoning mode. */
    public AiProviderCapabilities(
            AiStructuredOutputEnforcement structuredOutput,
            AiReasoningMode reasoning,
            int contextWindowTokens,
            int maxOutputTokens,
            AiToolCallingMode toolCalling,
            AiReasoningMode nativeToolReasoning) {
        this(structuredOutput, reasoning, contextWindowTokens, maxOutputTokens,
                toolCalling, nativeToolReasoning, false);
    }

    /**
     * Converts a token-denominated context window into the shared UTF-8 byte estimate.
     * @param contextTokens provider context-window tokens
     * @param outputTokens output tokens reserved from that context
     * @return estimated UTF-8 bytes available for serialized provider input
     */
    public static int estimatedInputByteCeiling(int contextTokens, int outputTokens) {
        int inputTokens = inputTokenBudget(contextTokens, outputTokens);
        return inputTokens > Integer.MAX_VALUE / ESTIMATED_UTF8_BYTES_PER_TOKEN
                ? Integer.MAX_VALUE
                : inputTokens * ESTIMATED_UTF8_BYTES_PER_TOKEN;
    }

    /**
     * Converts a token-denominated context window into a conservative UTF-8 admission ceiling.
     * @param contextTokens provider context-window tokens
     * @param outputTokens output tokens reserved from that context
     * @return maximum admitted UTF-8 bytes under the dense one-token-per-byte assumption
     */
    public static int conservativeInputByteCeiling(int contextTokens, int outputTokens) {
        return inputTokenBudget(contextTokens, outputTokens);
    }

    /**
     * Converts a UTF-8 byte reservation back into the shared token estimate.
     * @param utf8Bytes serialized UTF-8 bytes to reserve
     * @return estimated tokens required for the byte reservation
     */
    public static int estimatedTokensForBytes(int utf8Bytes) {
        if (utf8Bytes < 0) {
            throw new IllegalArgumentException("AI input byte budget must be non-negative");
        }
        return utf8Bytes / ESTIMATED_UTF8_BYTES_PER_TOKEN
                + (utf8Bytes % ESTIMATED_UTF8_BYTES_PER_TOKEN == 0 ? 0 : 1);
    }

    private static int inputTokenBudget(int contextTokens, int outputTokens) {
        if (contextTokens < 1 || outputTokens < 0) {
            throw new IllegalArgumentException("AI context and output budgets must be non-negative");
        }
        return Math.max(
                1,
                contextTokens - Math.min(contextTokens - 1, outputTokens));
    }
}
