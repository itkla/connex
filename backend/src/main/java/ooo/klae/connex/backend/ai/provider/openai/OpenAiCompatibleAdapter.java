package ooo.klae.connex.backend.ai.provider.openai;

import java.net.URI;
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
import ooo.klae.connex.backend.ai.provider.OpenAiChatParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapter for customer-supplied OpenAI-compatible chat-completions endpoints. The adapter
 * revalidates the configured base URI, preserves its authority and path, translates Connex's
 * narrow provider request, and normalizes the response.
 */
@Service
@RequiredArgsConstructor
public class OpenAiCompatibleAdapter implements AiProvider {
    private static final String PROVIDER_OPENAI_COMPATIBLE = "openai_compatible";
    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final ObjectMapper objectMapper;

    @Override
    public String providerId() {
        return PROVIDER_OPENAI_COMPATIBLE;
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiProviderTarget target = request.target();
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        try {
            URI endpoint = buildCompletionEndpoint(target);
            String requestBody = buildRequestBody(request);
            String responseBody = openAiCompatibleClient.complete(endpoint, target.allowInternalEndpoint(),
                    request.credentials(), requestBody);
            return parseResponse(responseBody);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("OpenAI-compatible adapter failed");
        }
    }

    private URI buildCompletionEndpoint(AiProviderTarget target) {
        String endpoint = target.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw invalidEndpoint();
        }
        try {
            URI base = URI.create(endpoint.trim());
            requireBaseEndpoint(base, target.allowInternalEndpoint());
            String rawPath = base.getRawPath();
            String basePath = rawPath == null ? "" : rawPath;
            while (basePath.endsWith("/")) {
                basePath = basePath.substring(0, basePath.length() - 1);
            }
            StringBuilder completionEndpoint = new StringBuilder()
                    .append(base.getScheme())
                    .append("://")
                    .append(base.getRawAuthority())
                    .append(basePath)
                    .append(COMPLETIONS_PATH);
            if (base.getRawQuery() != null) {
                completionEndpoint.append('?').append(base.getRawQuery());
            }
            return URI.create(completionEndpoint.toString());
        } catch (AiProviderException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidEndpoint();
        }
    }

    private String buildRequestBody(AiCompletionRequest request) throws Exception {
        String modelId = request.target().modelId();
        if (modelId == null || modelId.isBlank()) {
            throw new AiProviderException("OpenAI-compatible model id is required");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelId);
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (imagesPending && "user".equals(message.role())) {
                ArrayNode content = node.putArray("content");
                content.addObject().put("type", "text").put("text", message.content());
                for (AiInputImage image : request.images()) {
                    ObjectNode imageUrl = content.addObject()
                            .put("type", "image_url")
                            .putObject("image_url");
                    imageUrl.put("url", dataUrl(image));
                    imageUrl.put("detail", "high");
                }
                imagesPending = false;
            } else {
                node.put("content", message.content());
            }
        }
        if (imagesPending) {
            throw new AiProviderException("AI images require a user message");
        }
        if (OpenAiChatParameters.usesReasoningDialect(modelId)) {
            root.put("max_completion_tokens", request.maxTokens());
        } else {
            root.put("max_tokens", request.maxTokens());
            root.put("temperature", request.temperature());
        }
        return objectMapper.writeValueAsString(root);
    }

    private static String dataUrl(AiInputImage image) {
        return "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.content());
    }

    private AiCompletionResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty() || !choices.path(0).isObject()) {
                throw invalidResponse();
            }
            JsonNode choice = choices.path(0);
            JsonNode message = choice.path("message");
            if (!message.isObject()) {
                throw invalidResponse();
            }
            String text = readRequiredText(message.path("content"));
            int inputTokens = 0;
            int outputTokens = 0;
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                if (!usage.isObject()) {
                    throw invalidResponse();
                }
                inputTokens = readOptionalTokenCount(usage.path("prompt_tokens"));
                outputTokens = readOptionalTokenCount(usage.path("completion_tokens"));
            }
            String stopReason = readRequiredText(choice.path("finish_reason"));
            return new AiCompletionResult(text, inputTokens, outputTokens, stopReason);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private static void requireBaseEndpoint(URI endpoint, boolean allowInternalEndpoint) {
        String scheme = endpoint.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw invalidEndpoint();
        }
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) {
            throw invalidEndpoint();
        }
        if ("http".equalsIgnoreCase(scheme) && !allowInternalEndpoint) {
            throw invalidEndpoint();
        }
    }

    private static String readRequiredText(JsonNode node) {
        if (!node.isString()) {
            throw invalidResponse();
        }
        String value = node.asString();
        if (value.isEmpty()) {
            throw invalidResponse();
        }
        return value;
    }

    private static int readOptionalTokenCount(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return 0;
        }
        int value = node.intValue();
        return value < 0 ? 0 : value;
    }

    private static AiProviderException invalidEndpoint() {
        return new AiProviderException("Invalid OpenAI-compatible endpoint");
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("OpenAI-compatible response was invalid");
    }
}
