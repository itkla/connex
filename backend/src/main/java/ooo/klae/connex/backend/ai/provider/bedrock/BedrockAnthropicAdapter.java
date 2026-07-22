package ooo.klae.connex.backend.ai.provider.bedrock;

import java.util.Base64;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
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

    @Override
    public String providerId() {
        return PROVIDER_BEDROCK;
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
            String requestBody = buildRequestBody(request);
            String responseBody = bedrockClient.invokeModel(region, target.modelId(), request.credentials(), requestBody);
            return parseResponse(responseBody);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Bedrock Anthropic adapter failed", exception);
        }
    }

    private String buildRequestBody(AiCompletionRequest request) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("anthropic_version", ANTHROPIC_VERSION);
        root.put("max_tokens", request.maxTokens());
        root.put("temperature", request.temperature());
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
        return objectMapper.writeValueAsString(root);
    }

    private AiCompletionResult parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String text = readText(root);
        int inputTokens = readRequiredInt(root.path("usage").path("input_tokens"), "input token count");
        int outputTokens = readRequiredInt(root.path("usage").path("output_tokens"), "output token count");
        String stopReason = root.path("stop_reason").asString(null);
        return new AiCompletionResult(text, inputTokens, outputTokens, stopReason);
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

    private static int readRequiredInt(JsonNode node, String fieldName) {
        int value = node.asInt(-1);
        if (value < 0) {
            throw new AiProviderException("Bedrock Anthropic response did not include " + fieldName);
        }
        return value;
    }
}
