package ooo.klae.connex.backend.ai.assistant;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiProviderException;

/** Provider-aware independent UTF-8 byte budgets for one Ask Connex model step. */
public record AiAssistantPromptBudget(
        int maxOutputTokens,
        int historyBytes,
        int pageContextBytes,
        int toolResultBytes,
        int compactionSourceBytes) {

    private static final int BYTES_PER_TOKEN = 1;
    private static final int MIN_CONTEXT_TOKENS = 32_768;
    private static final int MAX_MASKED_SERIALIZATION_EXPANSION = 12;

    public AiAssistantPromptBudget {
        if (maxOutputTokens < 1 || historyBytes < 1 || pageContextBytes < 1
                || toolResultBytes < 1 || compactionSourceBytes < 1) {
            throw new IllegalArgumentException("Assistant prompt budgets must be positive");
        }
    }

    /**
     * Derives conservative input allocations from the configured adapter context window.
     * @param capabilities exact configured provider capabilities
     * @param configuredMaxOutputTokens operator-configured output ceiling
     * @return separate history, page-context, tool-result, and compaction allocations
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
     * @return separate history, page-context, tool-result, and compaction allocations
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
        int providerInputBytes = saturatedMultiply(
                contextTokens - maxOutputTokens, BYTES_PER_TOKEN);
        int variableEnvelopeBytes = providerInputBytes - fixedEnvelopeBytes;
        if (variableEnvelopeBytes < 4) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt exceeds the configured model context window");
        }
        int inputBytes = Math.max(
                3,
                variableEnvelopeBytes / MAX_MASKED_SERIALIZATION_EXPANSION);
        int historyBytes = Math.max(1, inputBytes / 2);
        int pageContextBytes = Math.max(1, inputBytes / 5);
        int toolResultBytes = Math.max(1, inputBytes - historyBytes - pageContextBytes);
        return new AiAssistantPromptBudget(
                maxOutputTokens,
                historyBytes,
                pageContextBytes,
                toolResultBytes,
                inputBytes);
    }

    private static int saturatedMultiply(int value, int multiplier) {
        return value > Integer.MAX_VALUE / multiplier
                ? Integer.MAX_VALUE
                : value * multiplier;
    }
}
