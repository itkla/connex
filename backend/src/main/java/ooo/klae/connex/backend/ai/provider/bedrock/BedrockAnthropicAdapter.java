package ooo.klae.connex.backend.ai.provider.bedrock;

import java.util.Base64;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic Claude adapter for Amazon Bedrock Runtime. The adapter translates Connex's narrow
 * provider request into Anthropic's Bedrock message JSON and normalizes the JSON response.
 */
@Service
@RequiredArgsConstructor
public class BedrockAnthropicAdapter implements AiProvider {
    private static final String PROVIDER_BEDROCK = "bedrock";
    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";

    private final BedrockClient bedrockClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    @Override
    public String providerId() {
        return PROVIDER_BEDROCK;
    }

    @Override
    public AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target) {
        return target != null && supportsStructuredOutput(target.modelId())
                ? AiStructuredOutputEnforcement.JSON_SCHEMA
                : AiStructuredOutputEnforcement.PROMPT_ONLY;
    }

    @Override
    public AiReasoningMode reasoningCapability(AiProviderTarget target) {
        return target != null && supportsNativeReasoning(target.modelId())
                ? AiReasoningMode.NATIVE
                : AiReasoningMode.TAGGED;
    }

    @Override
    public int contextWindowTokens(AiProviderTarget target) {
        if (target == null || target.modelId() == null) {
            return 4_096;
        }
        String normalized = target.modelId().toLowerCase(Locale.ROOT);
        return normalized.contains("claude-3")
                || normalized.contains("claude-sonnet-4")
                || normalized.contains("claude-opus-4")
                || normalized.contains("claude-haiku-4")
                || normalized.contains("claude-mythos")
                || normalized.contains("claude-fable")
                ? 200_000
                : 4_096;
    }

    /**
     * Resolves Bedrock's documented Claude output ceiling for the configured family.
     * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-3-haiku.html">Claude 3 Haiku limits</a>
     * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-3-5-haiku.html">Claude 3.5 Haiku limits</a>
     * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-4.html">Claude Sonnet 4 limits</a>
     * @see <a href="https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-opus-4-6.html">Claude Opus 4.6 limits</a>
     */
    @Override
    public int maxOutputTokens(AiProviderTarget target) {
        return anthropicMaxOutputTokens(target == null ? null : target.modelId());
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiProviderTarget target = request.target();
        if (!PROVIDER_BEDROCK.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        BedrockRegion region = BedrockRegion.fromCode(target.region());
        try {
            AiRequestDeadline deadline = request.providerAttemptExecutor()
                    .deadline(aiProperties.getRequestTimeoutMs());
            AiStructuredOutputEnforcement enforcement = requestedEnforcement(request);
            String requestBody = buildRequestBody(request, enforcement);
            String responseBody = request.providerAttemptExecutor().execute(() ->
                    bedrockClient.invokeModel(
                            region, target.modelId(), request.credentials(), requestBody, deadline));
            return parseResponse(responseBody, enforcement, request.reasoningMode());
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Bedrock Anthropic adapter failed", exception);
        }
    }

    private String buildRequestBody(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("anthropic_version", ANTHROPIC_VERSION);
        root.put("max_tokens", request.maxTokens());
        if (request.reasoningMode() == AiReasoningMode.NATIVE) {
            addNativeReasoning(root, request.target().modelId(), request.maxTokens());
        } else {
            root.put("temperature", request.temperature());
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("system", request.systemPrompt());
        }
        ArrayNode messages = root.putArray("messages");
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (imagesPending && "user".equals(message.role())) {
                ArrayNode content = node.putArray("content");
                for (AiInputImage image : request.images()) {
                    ObjectNode source = content.addObject()
                            .put("type", "image")
                            .putObject("source");
                    source.put("type", "base64");
                    source.put("media_type", image.contentType());
                    source.put("data", Base64.getEncoder().encodeToString(image.content()));
                }
                content.addObject().put("type", "text").put("text", message.content());
                imagesPending = false;
            } else {
                node.put("content", message.content());
            }
        }
        if (imagesPending) {
            throw new AiProviderException("AI images require a user message");
        }
        if (enforcement == AiStructuredOutputEnforcement.JSON_SCHEMA) {
            if (request.responseSchema() == null) {
                throw new AiProviderException("AI response schema is required");
            }
            ObjectNode format = root.putObject("output_config").putObject("format");
            format.put("type", "json_schema");
            format.set("schema", request.responseSchema().schema());
        }
        return objectMapper.writeValueAsString(root);
    }

    private AiCompletionResult parseResponse(
            String responseBody,
            AiStructuredOutputEnforcement enforcement,
            AiReasoningMode reasoningMode) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String text = readText(root);
        String reasoning = readReasoning(root);
        int inputTokens = readRequiredInt(root.path("usage").path("input_tokens"), "input token count");
        int outputTokens = readRequiredInt(root.path("usage").path("output_tokens"), "output token count");
        String stopReason = root.path("stop_reason").asString(null);
        return new AiCompletionResult(
                text, inputTokens, outputTokens, stopReason, enforcement,
                reasoning, reasoningMode);
    }

    private AiStructuredOutputEnforcement requestedEnforcement(
            AiCompletionRequest request) {
        if (request.reasoningMode() != AiReasoningMode.NONE) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        return request.outputMode() == AiOutputMode.JSON
                && request.responseSchema() != null
                ? structuredOutputCapability(request.target())
                : AiStructuredOutputEnforcement.PROMPT_ONLY;
    }

    private static boolean supportsStructuredOutput(String modelId) {
        if (modelId == null) {
            return false;
        }
        String normalized = modelId.toLowerCase(Locale.ROOT);
        return normalized.contains("claude-sonnet-4-5")
                || normalized.contains("claude-haiku-4-5")
                || normalized.contains("claude-opus-4-5")
                || normalized.contains("claude-opus-4-6");
    }

    private static boolean supportsNativeReasoning(String modelId) {
        if (modelId == null) {
            return false;
        }
        String normalized = modelId.toLowerCase(Locale.ROOT);
        return normalized.contains("claude-3-7-sonnet")
                || normalized.contains("claude-sonnet-4")
                || normalized.contains("claude-opus-4")
                || normalized.contains("claude-haiku-4-5")
                || normalized.contains("claude-mythos")
                || normalized.contains("claude-fable");
    }

    private static int anthropicMaxOutputTokens(String modelId) {
        if (modelId == null) {
            return 4_096;
        }
        String normalized = modelId.toLowerCase(Locale.ROOT);
        if (normalized.contains("claude-mythos")
                || normalized.contains("claude-fable")
                || normalized.contains("claude-sonnet-5")
                || normalized.contains("claude-opus-5")
                || normalized.contains("claude-haiku-5")
                || normalized.contains("claude-opus-4-6")
                || normalized.contains("claude-opus-4-7")
                || normalized.contains("claude-opus-4-8")
                || normalized.contains("claude-sonnet-4-6")) {
            return 131_072;
        }
        if (normalized.contains("claude-3-7")
                || normalized.contains("claude-sonnet-4")
                || normalized.contains("claude-haiku-4")
                || normalized.contains("claude-opus-4-5")) {
            return 65_536;
        }
        if (normalized.contains("claude-opus-4")) {
            return 32_768;
        }
        if (normalized.contains("claude-3-5")) {
            return 8_192;
        }
        return 4_096;
    }

    private static void addNativeReasoning(
            ObjectNode root, String modelId, int maxTokens) {
        if (maxTokens <= 1_024) {
            throw new AiProviderException("Bedrock reasoning requires more than 1024 output tokens");
        }
        String normalized = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        ObjectNode thinking = root.putObject("thinking");
        if (normalized.contains("claude-mythos")
                || normalized.contains("claude-fable")
                || normalized.contains("claude-opus-4-7")
                || normalized.contains("claude-opus-4-6")
                || normalized.contains("claude-sonnet-4-6")) {
            thinking.put("type", "adaptive");
            return;
        }
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", 1_024);
    }

    private static String readText(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            throw new AiProviderException("Bedrock Anthropic response did not include content");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asString(null);
            String value = block.path("text").asString(null);
            if ((type == null || "text".equals(type)) && value != null) {
                text.append(value);
            }
        }
        if (text.isEmpty()) {
            throw new AiProviderException("Bedrock Anthropic response did not include text");
        }
        return text.toString();
    }

    private static String readReasoning(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder reasoning = new StringBuilder();
        for (JsonNode block : content) {
            if (!"thinking".equals(block.path("type").asString(null))) {
                continue;
            }
            String value = block.path("thinking").asString(null);
            if (value != null && !value.isBlank()) {
                if (!reasoning.isEmpty()) {
                    reasoning.append("\n\n");
                }
                reasoning.append(value.strip());
            }
        }
        return reasoning.toString();
    }

    private static int readRequiredInt(JsonNode node, String fieldName) {
        int value = node.asInt(-1);
        if (value < 0) {
            throw new AiProviderException("Bedrock Anthropic response did not include " + fieldName);
        }
        return value;
    }
}
