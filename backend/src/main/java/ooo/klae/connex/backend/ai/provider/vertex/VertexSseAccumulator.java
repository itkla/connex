package ooo.klae.connex.backend.ai.provider.vertex;

import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reassembles Vertex Gemini streamGenerateContent SSE events. */
public final class VertexSseAccumulator {
    private final ObjectMapper objectMapper;
    private final AiProviderStreamObserver observer;
    private final AiStructuredOutputEnforcement enforcement;
    private final AiReasoningMode reasoningMode;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private int inputTokens;
    private int outputTokens;
    private String finishReason;

    public VertexSseAccumulator(
            ObjectMapper objectMapper,
            AiProviderStreamObserver observer,
            AiStructuredOutputEnforcement enforcement,
            AiReasoningMode reasoningMode) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.observer = java.util.Objects.requireNonNull(observer, "observer");
        this.enforcement = java.util.Objects.requireNonNull(enforcement, "enforcement");
        this.reasoningMode = java.util.Objects.requireNonNull(reasoningMode, "reasoningMode");
    }

    /** Accepts one decoded Vertex SSE data event. */
    public void accept(String event) {
        try {
            JsonNode root = objectMapper.readTree(event);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray()) {
                throw invalidResponse();
            }
            validateEvent(root, candidates);
            if (!candidates.isEmpty()) {
                readCandidate(candidates.path(0));
            }
            readUsage(root.get("usageMetadata"));
            observer.onNetworkChunk();
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    /** Returns the complete normalized response after stream EOF. */
    public AiCompletionResult finish() {
        if (text.isEmpty() || finishReason == null) {
            throw invalidResponse();
        }
        return new AiCompletionResult(
                text.toString(), inputTokens, outputTokens, finishReason, enforcement,
                reasoning.toString(), reasoningMode);
    }

    /** Reports decoded SSE transport activity before event assembly. */
    public void onTransportActivity() {
        observer.onNetworkChunk();
    }

    private static void validateEvent(JsonNode root, JsonNode candidates) {
        validateUsage(root.get("usageMetadata"));
        if (candidates.isEmpty()) {
            return;
        }
        JsonNode candidate = candidates.path(0);
        if (!candidate.isObject()) {
            throw invalidResponse();
        }
        JsonNode content = candidate.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isObject()) {
                throw invalidResponse();
            }
            JsonNode parts = content.get("parts");
            if (parts != null && !parts.isNull() && !parts.isArray()) {
                throw invalidResponse();
            }
            if (parts != null && parts.isArray()) {
                for (JsonNode part : parts) {
                    if (!part.isObject()) {
                        throw invalidResponse();
                    }
                    JsonNode textNode = part.get("text");
                    if (textNode != null && !textNode.isNull() && !textNode.isString()) {
                        throw invalidResponse();
                    }
                    JsonNode thought = part.get("thought");
                    if (thought != null && !thought.isNull() && !thought.isBoolean()) {
                        throw invalidResponse();
                    }
                }
            }
        }
        JsonNode finish = candidate.get("finishReason");
        if (finish != null && !finish.isNull() && !finish.isString()) {
            throw invalidResponse();
        }
    }

    private static void validateUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return;
        }
        if (!usage.isObject()) {
            throw invalidResponse();
        }
        int candidates = optionalNonNegativeInt(usage.get("candidatesTokenCount"));
        int thoughts = optionalNonNegativeInt(usage.get("thoughtsTokenCount"));
        optionalNonNegativeInt(usage.get("promptTokenCount"));
        add(candidates, thoughts);
    }

    private void readCandidate(JsonNode candidate) {
        if (!candidate.isObject()) {
            throw invalidResponse();
        }
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode textNode = part.get("text");
                if (textNode == null || textNode.isNull()) {
                    continue;
                }
                if (!textNode.isString()) {
                    throw invalidResponse();
                }
                String value = textNode.asString();
                if (part.path("thought").asBoolean(false)) {
                    reasoning.append(value);
                } else if (!value.isEmpty()) {
                    text.append(value);
                    observer.onContentDelta(value);
                }
            }
        }
        JsonNode finish = candidate.get("finishReason");
        if (finish != null && !finish.isNull()) {
            if (!finish.isString()) {
                throw invalidResponse();
            }
            finishReason = finish.asString();
        }
    }

    private void readUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) {
            return;
        }
        if (!usage.isObject()) {
            throw invalidResponse();
        }
        inputTokens = optionalNonNegativeInt(usage.get("promptTokenCount"));
        outputTokens = add(
                optionalNonNegativeInt(usage.get("candidatesTokenCount")),
                optionalNonNegativeInt(usage.get("thoughtsTokenCount")));
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

    private static int add(int left, int right) {
        if (left > Integer.MAX_VALUE - right) {
            throw invalidResponse();
        }
        return left + right;
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("Vertex streaming response was invalid");
    }
}
