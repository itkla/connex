package ooo.klae.connex.backend.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/** Provider-aware independent UTF-8 byte budgets for one Ask Connex model step. */
public record AiAssistantPromptBudget(
        int maxOutputTokens,
        int historyBytes,
        int attachmentContextBytes,
        int pageContextBytes,
        int toolResultBytes,
        int compactionSourceBytes) {

    private static final int MIN_CONTEXT_TOKENS = 32_768;
    private static final int MIN_HISTORY_BYTES = 4_096;
    private static final int MIN_ATTACHMENT_CONTEXT_BYTES = 256;
    private static final int MIN_PAGE_CONTEXT_BYTES = 256;
    private static final int MIN_TOOL_RESULT_BYTES = 2_048;
    private static final int MIN_VARIABLE_INPUT_BYTES = MIN_HISTORY_BYTES
            + MIN_ATTACHMENT_CONTEXT_BYTES + MIN_PAGE_CONTEXT_BYTES
            + MIN_TOOL_RESULT_BYTES;
    private static final int MAX_MASKED_SERIALIZATION_EXPANSION = 12;

    public AiAssistantPromptBudget {
        if (maxOutputTokens < 1 || historyBytes < 1 || attachmentContextBytes < 1
                || pageContextBytes < 1
                || toolResultBytes < 1 || compactionSourceBytes < 1) {
            throw new IllegalArgumentException("Assistant prompt budgets must be positive");
        }
    }

    /** @return minimum replay bytes reserved for the latest tool result */
    public int minimumToolResultBytes() {
        return Math.min(toolResultBytes, MIN_TOOL_RESULT_BYTES);
    }

    /** Returns the exact UTF-8 size used by assistant prompt allocations. */
    public int utf8Bytes(String value) {
        return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8).length;
    }

    /** Returns whether the supplied content fits the remaining byte allocation. */
    public boolean fits(String value, int availableBytes) {
        return availableBytes >= 0 && utf8Bytes(value) <= availableBytes;
    }

    /** Truncates text to a valid UTF-8 prefix within the supplied byte allocation. */
    public String truncateUtf8(String value, int availableBytes) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (availableBytes <= 0) {
            return "";
        }
        if (bytes.length <= availableBytes) {
            return value;
        }
        int end = availableBytes;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Derives conservative input allocations from the configured adapter context window.
     * @param capabilities exact configured provider capabilities
     * @param configuredMaxOutputTokens operator-configured output ceiling
     * @return separate history, attachment, page-context, tool-result, and compaction allocations
     */
    public static AiAssistantPromptBudget from(
            AiProviderCapabilities capabilities,
            int configuredMaxOutputTokens) {
        return from(capabilities, configuredMaxOutputTokens, 0);
    }

    /**
     * Derives allocations after subtracting the exact serialized fixed prompt envelope.
     * @param capabilities exact configured provider capabilities
     * @param configuredMaxOutputTokens operator-configured output ceiling
     * @param fixedEnvelopeBytes exact serialized system, schema, and reasoning envelope bytes
     * @return separate history, attachment, page-context, tool-result, and compaction allocations
     */
    public static AiAssistantPromptBudget from(
            AiProviderCapabilities capabilities,
            int configuredMaxOutputTokens,
            int fixedEnvelopeBytes) {
        if (capabilities == null) {
            throw new IllegalArgumentException("AI provider capabilities are required");
        }
        if (configuredMaxOutputTokens < 1) {
            throw new IllegalArgumentException("Configured output budget must be positive");
        }
        if (fixedEnvelopeBytes < 0) {
            throw new IllegalArgumentException("Fixed prompt envelope cannot be negative");
        }
        int contextTokens = capabilities.contextWindowTokens();
        if (contextTokens < MIN_CONTEXT_TOKENS) {
            throw new AiProviderException(
                    "Ask Connex requires a model context window of at least 32768 tokens");
        }
        int providerOutputCeiling = Math.max(1_024, contextTokens / 4);
        int maxOutputTokens = Math.min(configuredMaxOutputTokens, providerOutputCeiling);
        int minimumSerializedInputBytes = saturatedAdd(
                fixedEnvelopeBytes,
                saturatedMultiply(
                        MIN_VARIABLE_INPUT_BYTES, MAX_MASKED_SERIALIZATION_EXPANSION));
        int floorPreservingOutputTokens = contextTokens
                - AiProviderCapabilities.estimatedTokensForBytes(minimumSerializedInputBytes);
        if (floorPreservingOutputTokens < 1) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt leaves no usable model input budget");
        }
        maxOutputTokens = Math.min(maxOutputTokens, floorPreservingOutputTokens);
        int providerInputBytes = AiProviderCapabilities.estimatedInputByteCeiling(
                contextTokens, maxOutputTokens);
        int variableEnvelopeBytes = providerInputBytes - fixedEnvelopeBytes;
        if (variableEnvelopeBytes < MIN_VARIABLE_INPUT_BYTES) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt exceeds the configured model context window");
        }
        int inputBytes = variableEnvelopeBytes / MAX_MASKED_SERIALIZATION_EXPANSION;
        if (inputBytes < MIN_VARIABLE_INPUT_BYTES) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt leaves no usable variable input budget");
        }
        int distributableBytes = inputBytes - MIN_VARIABLE_INPUT_BYTES;
        int historyBytes = MIN_HISTORY_BYTES + distributableBytes / 2;
        int attachmentContextBytes = MIN_ATTACHMENT_CONTEXT_BYTES
                + distributableBytes / 5;
        int pageContextBytes = MIN_PAGE_CONTEXT_BYTES + distributableBytes / 10;
        int toolResultBytes = inputBytes
                - historyBytes - attachmentContextBytes - pageContextBytes;
        return new AiAssistantPromptBudget(
                maxOutputTokens,
                historyBytes,
                attachmentContextBytes,
                pageContextBytes,
                toolResultBytes,
                inputBytes);
    }

    private static int saturatedMultiply(int value, int multiplier) {
        return value > Integer.MAX_VALUE / multiplier
                ? Integer.MAX_VALUE
                : value * multiplier;
    }

    private static int saturatedAdd(int left, int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }
}
