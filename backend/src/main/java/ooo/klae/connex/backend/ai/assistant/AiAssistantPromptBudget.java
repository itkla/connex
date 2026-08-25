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
        int repairEnvelopeBytes,
        int compactionSourceBytes,
        boolean outputTokensClamped) {

    /**
     * The smallest provider context window Ask Connex will run a turn on.
     *
     * <p>The fixed prompt envelope — the tool vocabulary in the system instructions plus the same
     * catalog again as the strict step response schema — is paid out of the output allocation, and
     * the conservative term reduces to {@code outputTokens = 17,920 - fixedEnvelopeBytes} on a 32k
     * window. The measured cliff is therefore {@code 17,919} bytes, where the turn retains a single
     * output token and cannot start. Today's JSON-ReAct envelope measures {@code 16,962} bytes,
     * which leaves a 32k model {@code 958} output tokens: enough to begin an answer document and
     * not enough to finish one, so the model stops mid-sentence and the reader is shown a truncated
     * answer that looks complete.
     *
     * <p>The settled choice (issue #1420) is to raise the floor rather than split the tool
     * vocabulary into a smaller 32k dialect. A second vocabulary would mean two prompt contracts,
     * two schemas, and two sets of answer-quality expectations for the same product surface, and
     * the smaller one would still be the dialect that quietly truncates. An organization whose
     * configured model is below this floor gets an honest per-turn refusal
     * ({@link AiAssistantTerminalReasons#CONTEXT_WINDOW_TOO_SMALL}) instead. Only ASSISTANT_CHAT is
     * held to this floor; deal briefs, risk rationales, and report narratives assemble a far
     * smaller envelope and keep serving 32k models unchanged.
     */
    public static final int ASSISTANT_MIN_CONTEXT_TOKENS = 65_536;
    private static final int MIN_HISTORY_BYTES = 4_096;
    private static final int MIN_ATTACHMENT_CONTEXT_BYTES = 256;
    private static final int MIN_PAGE_CONTEXT_BYTES = 256;
    private static final int MIN_TOOL_RESULT_BYTES = 2_048;
    private static final int DEFAULT_REPAIR_ENVELOPE_BYTES = 8_192;
    private static final int MIN_VARIABLE_INPUT_BYTES = MIN_HISTORY_BYTES
            + MIN_ATTACHMENT_CONTEXT_BYTES + MIN_PAGE_CONTEXT_BYTES
            + MIN_TOOL_RESULT_BYTES;
    private static final int MAX_MASKED_SERIALIZATION_EXPANSION = 12;

    public AiAssistantPromptBudget(
            int maxOutputTokens,
            int historyBytes,
            int attachmentContextBytes,
            int pageContextBytes,
            int toolResultBytes,
            int compactionSourceBytes) {
        this(maxOutputTokens, historyBytes, attachmentContextBytes, pageContextBytes,
                toolResultBytes, DEFAULT_REPAIR_ENVELOPE_BYTES, compactionSourceBytes, false);
    }

    public AiAssistantPromptBudget(
            int maxOutputTokens,
            int historyBytes,
            int attachmentContextBytes,
            int pageContextBytes,
            int toolResultBytes,
            int compactionSourceBytes,
            boolean outputTokensClamped) {
        this(maxOutputTokens, historyBytes, attachmentContextBytes, pageContextBytes,
                toolResultBytes, DEFAULT_REPAIR_ENVELOPE_BYTES, compactionSourceBytes,
                outputTokensClamped);
    }

    public AiAssistantPromptBudget(
            int maxOutputTokens,
            int historyBytes,
            int attachmentContextBytes,
            int pageContextBytes,
            int toolResultBytes,
            int repairEnvelopeBytes,
            int compactionSourceBytes) {
        this(maxOutputTokens, historyBytes, attachmentContextBytes, pageContextBytes,
                toolResultBytes, repairEnvelopeBytes, compactionSourceBytes, false);
    }

    public AiAssistantPromptBudget {
        if (maxOutputTokens < 1 || historyBytes < 1 || attachmentContextBytes < 1
                || pageContextBytes < 1
                || toolResultBytes < 1 || repairEnvelopeBytes < 1
                || compactionSourceBytes < 1) {
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
     * @return separate history, attachment, page-context, tool-result, repair, and compaction
     * allocations
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
     * @return separate history, attachment, page-context, tool-result, repair, and compaction
     * allocations
     * @throws AiAssistantLoopException when the configured context window is below
     * {@link #ASSISTANT_MIN_CONTEXT_TOKENS}, refusing the turn before any provider egress
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
        if (contextTokens < ASSISTANT_MIN_CONTEXT_TOKENS) {
            throw new AiAssistantLoopException(
                    AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                    AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL);
        }
        int maxOutputTokens = Math.min(
                configuredMaxOutputTokens, capabilities.maxOutputTokens());
        boolean outputTokensClamped = configuredMaxOutputTokens > capabilities.maxOutputTokens();
        int minimumExpandedInputBytes = saturatedAdd(
                saturatedAdd(fixedEnvelopeBytes, DEFAULT_REPAIR_ENVELOPE_BYTES),
                saturatedMultiply(
                        MIN_VARIABLE_INPUT_BYTES, MAX_MASKED_SERIALIZATION_EXPANSION));
        int minimumConservativeInputBytes = saturatedAdd(
                saturatedAdd(fixedEnvelopeBytes, DEFAULT_REPAIR_ENVELOPE_BYTES),
                MIN_VARIABLE_INPUT_BYTES);
        int floorPreservingOutputTokens = Math.min(
                contextTokens
                        - AiProviderCapabilities.estimatedTokensForBytes(
                                minimumExpandedInputBytes),
                contextTokens - minimumConservativeInputBytes);
        if (floorPreservingOutputTokens < 1) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt leaves no usable model input budget");
        }
        maxOutputTokens = Math.min(maxOutputTokens, floorPreservingOutputTokens);
        int providerInputBytes = AiProviderCapabilities.estimatedInputByteCeiling(
                contextTokens, maxOutputTokens);
        long variableEnvelopeBytes = (long) providerInputBytes
                - fixedEnvelopeBytes - DEFAULT_REPAIR_ENVELOPE_BYTES;
        if (variableEnvelopeBytes < MIN_VARIABLE_INPUT_BYTES) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt exceeds the configured model context window");
        }
        long conservativeVariableEnvelopeBytes = (long)
                AiProviderCapabilities.conservativeInputByteCeiling(
                        contextTokens, maxOutputTokens)
                - fixedEnvelopeBytes - DEFAULT_REPAIR_ENVELOPE_BYTES;
        long boundedInputBytes = Math.min(
                variableEnvelopeBytes / MAX_MASKED_SERIALIZATION_EXPANSION,
                conservativeVariableEnvelopeBytes);
        if (boundedInputBytes < MIN_VARIABLE_INPUT_BYTES) {
            throw new AiProviderException(
                    "Ask Connex fixed prompt leaves no usable variable input budget");
        }
        int inputBytes = (int) boundedInputBytes;
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
                DEFAULT_REPAIR_ENVELOPE_BYTES,
                inputBytes,
                outputTokensClamped);
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
