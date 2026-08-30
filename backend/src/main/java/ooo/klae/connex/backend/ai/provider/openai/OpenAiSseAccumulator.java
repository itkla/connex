package ooo.klae.connex.backend.ai.provider.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reassembles OpenAI chat-completion SSE deltas into one normalized bounded result. */
public final class OpenAiSseAccumulator {
    private final ObjectMapper objectMapper;
    private final AiProviderStreamObserver observer;
    private final AiStructuredOutputEnforcement enforcement;
    private final AiReasoningMode reasoningMode;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, ToolFragments> tools = new TreeMap<>();
    private int impliedToolCalls;
    private Boolean toolCallsImplied;
    private int inputTokens;
    private int outputTokens;
    private String stopReason;
    private boolean done;

    public OpenAiSseAccumulator(
            ObjectMapper objectMapper,
            AiProviderStreamObserver observer,
            AiStructuredOutputEnforcement enforcement,
            AiReasoningMode reasoningMode) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.observer = java.util.Objects.requireNonNull(observer, "observer");
        this.enforcement = java.util.Objects.requireNonNull(enforcement, "enforcement");
        this.reasoningMode = java.util.Objects.requireNonNull(reasoningMode, "reasoningMode");
    }

    /** Accepts one decoded SSE data event. */
    public void accept(String event) {
        if ("[DONE]".equals(event)) {
            done = true;
            observer.onNetworkChunk();
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(event);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            readUsage(root.get("usage"));
            JsonNode choices = root.path("choices");
            if (!choices.isArray()) {
                throw invalidResponse();
            }
            validateEvent(root, choices);
            if (!choices.isEmpty()) {
                readChoice(choices.path(0));
            }
            observer.onNetworkChunk();
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    /** Returns the complete result after the terminal SSE marker. */
    public AiCompletionResult finish() {
        if (!done || stopReason == null || text.isEmpty() && tools.isEmpty()) {
            throw invalidResponse();
        }
        List<AiToolCall> toolCalls = new ArrayList<>();
        for (ToolFragments fragments : tools.values()) {
            toolCalls.add(fragments.build());
        }
        return new AiCompletionResult(
                text.toString(), inputTokens, outputTokens, stopReason, enforcement,
                reasoning.toString(), reasoningMode, toolCalls);
    }

    void openTransport(Runnable cancellation) {
        observer.onTransportOpen(cancellation);
    }

    void closeTransport() {
        observer.onTransportClosed();
    }

    /** Reports decoded SSE transport activity before event assembly. */
    public void onTransportActivity() {
        observer.onNetworkChunk();
    }

    private static void validateEvent(JsonNode root, JsonNode choices) {
        validateUsage(root.get("usage"));
        if (choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.path(0);
        if (!choice.isObject()) {
            throw invalidResponse();
        }
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) {
            throw invalidResponse();
        }
        validateOptionalText(delta.get("content"));
        JsonNode reasoningContent = delta.get("reasoning_content");
        validateOptionalText(
                reasoningContent == null || reasoningContent.isNull()
                        ? delta.get("reasoning")
                        : reasoningContent);
        validateToolDeltas(delta.get("tool_calls"));
        validateOptionalText(choice.get("finish_reason"));
    }

    private static void validateUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return;
        }
        if (!usage.isObject()) {
            throw invalidResponse();
        }
        optionalNonNegativeInt(usage.get("prompt_tokens"));
        optionalNonNegativeInt(usage.get("completion_tokens"));
    }

    private static void validateToolDeltas(JsonNode calls) {
        if (calls == null || calls.isNull()) {
            return;
        }
        if (!calls.isArray()) {
            throw invalidResponse();
        }
        for (JsonNode call : calls) {
            if (!call.isObject()) {
                throw invalidResponse();
            }
            JsonNode indexNode = call.path("index");
            if (impliedIndex(indexNode)) {
                requireWholeCall(call);
            } else if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()
                    || indexNode.intValue() < 0 || indexNode.intValue() > 63) {
                throw invalidResponse();
            }
            validateOptionalText(call.get("id"));
            JsonNode function = call.get("function");
            if (function != null && !function.isNull()) {
                if (!function.isObject()) {
                    throw invalidResponse();
                }
                validateOptionalText(function.get("name"));
                validateOptionalText(function.get("arguments"));
            }
            validateOptionalText(
                    call.path("extra_content").path("google").get("thought_signature"));
        }
    }

    /**
     * Whether a streamed tool call leaves its position to be inferred from arrival order.
     *
     * <p>OpenAI numbers every streamed tool call so that argument fragments can be routed back to
     * the call they belong to. Not every OpenAI-compatible endpoint sends that number, and one that
     * omits it is not malformed — it is describing calls that arrive whole and therefore need no
     * reassembly.
     */
    private static boolean impliedIndex(JsonNode indexNode) {
        return indexNode == null || indexNode.isMissingNode() || indexNode.isNull();
    }

    /**
     * Rejects an unnumbered tool call that is not complete on its own.
     *
     * <p>Arrival order can only stand in for an explicit position while each delta carries a whole
     * call. An unnumbered fragment names neither a call to continue nor a call to start, so taking it
     * would mean guessing — either splitting one call across two, or merging two into one. Neither
     * is recoverable afterwards, so it stays refused.
     */
    private static void requireWholeCall(JsonNode call) {
        JsonNode function = call.path("function");
        if (!function.isObject()
                || !function.path("name").isString()
                || !function.path("arguments").isString()) {
            throw invalidResponse();
        }
    }

    private static void validateOptionalText(JsonNode node) {
        if (node != null && !node.isNull() && !node.isString()) {
            throw invalidResponse();
        }
    }

    private void readChoice(JsonNode choice) {
        if (!choice.isObject()) {
            throw invalidResponse();
        }
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) {
            throw invalidResponse();
        }
        appendOptionalText(delta.get("content"), text, true);
        JsonNode reasoningContent = delta.get("reasoning_content");
        appendOptionalText(
                reasoningContent == null || reasoningContent.isNull()
                        ? delta.get("reasoning")
                        : reasoningContent,
                reasoning,
                false);
        readToolDeltas(delta.get("tool_calls"));
        JsonNode finish = choice.get("finish_reason");
        if (finish != null && !finish.isNull()) {
            if (!finish.isString()) {
                throw invalidResponse();
            }
            stopReason = finish.asString();
        }
    }

    private void appendOptionalText(JsonNode node, StringBuilder target, boolean publish) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isString()) {
            throw invalidResponse();
        }
        String value = node.asString();
        if (value.isEmpty()) {
            return;
        }
        target.append(value);
        if (publish) {
            observer.onContentDelta(value);
        }
    }

    private void readToolDeltas(JsonNode calls) {
        if (calls == null || calls.isNull()) {
            return;
        }
        if (!calls.isArray()) {
            throw invalidResponse();
        }
        for (JsonNode call : calls) {
            if (!call.isObject()) {
                throw invalidResponse();
            }
            JsonNode indexNode = call.path("index");
            boolean implied = impliedIndex(indexNode);
            requireConsistentNumbering(implied);
            int index;
            if (implied) {
                requireWholeCall(call);
                index = impliedToolCalls++;
            } else {
                if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                    throw invalidResponse();
                }
                index = indexNode.intValue();
            }
            if (index < 0 || index > 63) {
                throw invalidResponse();
            }
            ToolFragments fragments = tools.computeIfAbsent(index, ignored -> new ToolFragments());
            fragments.append(call);
        }
    }

    /**
     * Holds one response to a single convention for numbering its tool calls.
     *
     * <p>Unnumbered calls take their position from arrival order, so a response that numbers some
     * of its calls and not others gives two positions the same meaning and no way to tell which was
     * intended. Merging or splitting a tool call cannot be detected downstream — the arguments are
     * simply wrong — so a response that changes convention mid-stream is refused rather than
     * guessed at.
     */
    private void requireConsistentNumbering(boolean implied) {
        if (toolCallsImplied == null) {
            toolCallsImplied = implied;
        } else if (toolCallsImplied != implied) {
            throw invalidResponse();
        }
    }

    private void readUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return;
        }
        if (!usage.isObject()) {
            throw invalidResponse();
        }
        inputTokens = optionalNonNegativeInt(usage.get("prompt_tokens"));
        outputTokens = optionalNonNegativeInt(usage.get("completion_tokens"));
    }

    private static int optionalNonNegativeInt(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw invalidResponse();
        }
        return node.intValue();
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("OpenAI-compatible streaming response was invalid");
    }

    private static final class ToolFragments {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private final StringBuilder thoughtSignature = new StringBuilder();

        private void append(JsonNode call) {
            appendOptional(call.get("id"), id);
            JsonNode function = call.get("function");
            if (function != null && !function.isNull()) {
                if (!function.isObject()) {
                    throw invalidResponse();
                }
                appendOptional(function.get("name"), name);
                appendOptional(function.get("arguments"), arguments);
            }
            appendOptional(
                    call.path("extra_content").path("google").get("thought_signature"),
                    thoughtSignature);
        }

        private AiToolCall build() {
            try {
                return new AiToolCall(
                        required(id), required(name), required(arguments),
                        thoughtSignature.isEmpty() ? null : thoughtSignature.toString());
            } catch (IllegalArgumentException exception) {
                throw invalidResponse();
            }
        }

        private static void appendOptional(JsonNode node, StringBuilder target) {
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isString()) {
                throw invalidResponse();
            }
            target.append(node.asString());
        }

        private static String required(StringBuilder value) {
            if (value.isEmpty()) {
                throw invalidResponse();
            }
            return value.toString();
        }
    }
}
