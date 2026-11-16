package ooo.klae.connex.backend.ai.provider.bedrock;

import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.SERVER_STATIC;

import java.util.Base64;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiProviderGateExceptions;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderDispatch;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog;
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

    /**
     * Resolves the declared Bedrock Claude context window for the configured target.
     * @see AiModelCatalog
     */
    @Override
    public int contextWindowTokens(AiProviderTarget target) {
        return AiModelCatalog.contextWindowTokens(
                AiModelCatalog.Family.BEDROCK_ANTHROPIC, target, aiProperties.getModelOverrides());
    }

    /**
     * Resolves the declared Bedrock Claude output ceiling for the configured target.
     * @see AiModelCatalog
     */
    @Override
    public int maxOutputTokens(AiProviderTarget target) {
        return AiModelCatalog.maxOutputTokens(
                AiModelCatalog.Family.BEDROCK_ANTHROPIC, target, aiProperties.getModelOverrides());
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
            AiProviderDispatch requestBody = buildRequestBody(request, enforcement);
            String responseBody = request.providerAttemptExecutor().execute(() ->
                    bedrockClient.invokeModel(
                            region, target.modelId(), request.credentials(), requestBody.body(), deadline,
                                    requestBody));
            return parseResponse(responseBody, enforcement, request.reasoningMode());
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            AiProviderGateExceptions.rethrowIfGate(exception);
            throw new AiProviderException("Bedrock Anthropic adapter failed", exception);
        }
    }

    private AiProviderDispatch buildRequestBody(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement) throws Exception {
        AiProviderDispatch.Builder evidence = new AiProviderDispatch.Builder(objectMapper);
        ObjectNode root = objectMapper.createObjectNode();
        evidence.text(root, "anthropic_version", ANTHROPIC_VERSION, SERVER_STATIC);
        root.put("max_tokens", request.maxTokens());
        if (request.reasoningMode() == AiReasoningMode.NATIVE) {
            addNativeReasoning(root, request.target().modelId(), request.maxTokens(), evidence);
        } else {
            root.put("temperature", request.temperature());
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            evidence.text(root, "system", request.systemPrompt(), SERVER_STATIC);
        }
        ArrayNode messages = root.putArray("messages");
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            evidence.text(node, "role", message.role(), SERVER_STATIC);
            if (imagesPending && "user".equals(message.role())) {
                ArrayNode content = node.putArray("content");
                for (AiInputImage image : request.images()) {
                    ObjectNode imagePart = content.addObject();
                    evidence.text(imagePart, "type", "image", SERVER_STATIC);
                    ObjectNode source = imagePart.putObject("source");
                    evidence.text(source, "type", "base64", SERVER_STATIC);
                    evidence.text(source, "media_type", image.contentType(), SERVER_STATIC);
                    evidence.text(source, "data", Base64.getEncoder().encodeToString(image.content()), SERVER_STATIC);
                }
                ObjectNode text = content.addObject();
                evidence.text(text, "type", "text", SERVER_STATIC);
                evidence.text(text, "text", message.content(), message.segments());
                imagesPending = false;
            } else {
                evidence.text(node, "content", message.content(), message.segments());
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
            evidence.text(format, "type", "json_schema", SERVER_STATIC);
            evidence.schema(format, "schema", request.responseSchema().schema());
        }
        return evidence.build(root, request.providerAttemptExecutor());
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

    private static void addNativeReasoning(
            ObjectNode root, String modelId, int maxTokens, AiProviderDispatch.Builder evidence) {
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
            evidence.text(thinking, "type", "adaptive", SERVER_STATIC);
            return;
        }
        evidence.text(thinking, "type", "enabled", SERVER_STATIC);
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
