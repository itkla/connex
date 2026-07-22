package ooo.klae.connex.backend.ai.provider.azure;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

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
 * Azure OpenAI chat-completions adapter. The adapter constrains each destination to an Azure
 * OpenAI resource host, translates Connex's narrow provider request, and normalizes the response.
 */
@Service
@RequiredArgsConstructor
public class AzureOpenAiAdapter implements AiProvider {
    private static final String PROVIDER_AZURE_OPENAI = "azure_openai";
    private static final String AZURE_HOST_SUFFIX = ".openai.azure.com";
    private static final Pattern AZURE_DEPLOYMENT = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern AZURE_API_VERSION = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(-preview)?$");

    private final AzureOpenAiClient azureOpenAiClient;
    private final ObjectMapper objectMapper;

    @Override
    public String providerId() {
        return PROVIDER_AZURE_OPENAI;
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiProviderTarget target = request.target();
        if (!PROVIDER_AZURE_OPENAI.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        try {
            URI endpoint = buildCompletionEndpoint(target);
            String requestBody = buildRequestBody(request);
            String responseBody = azureOpenAiClient.complete(endpoint, request.credentials(), requestBody);
            return parseResponse(responseBody);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Azure OpenAI adapter failed");
        }
    }

    private URI buildCompletionEndpoint(AiProviderTarget target) {
        String host = requireAzureHost(target.endpoint());
        String deployment = requirePattern(target.deployment(), AZURE_DEPLOYMENT,
                "Invalid Azure OpenAI deployment");
        if (deployment.equals(".") || deployment.contains("..")) {
            throw new AiProviderException("Invalid Azure OpenAI deployment");
        }
        String apiVersion = requirePattern(target.apiVersion(), AZURE_API_VERSION,
                "Invalid Azure OpenAI API version");
        String encodedDeployment = URLEncoder.encode(deployment, StandardCharsets.UTF_8);
        String encodedApiVersion = URLEncoder.encode(apiVersion, StandardCharsets.UTF_8);
        return URI.create("https://" + host + "/openai/deployments/" + encodedDeployment
                + "/chat/completions?api-version=" + encodedApiVersion);
    }

    private String buildRequestBody(AiCompletionRequest request) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
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
        root.put("max_completion_tokens", request.maxTokens());
        if (!OpenAiChatParameters.usesReasoningDialect(request.target().modelId())) {
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
            JsonNode usage = root.path("usage");
            if (!usage.isObject()) {
                throw invalidResponse();
            }
            int inputTokens = readRequiredInt(usage.path("prompt_tokens"));
            int outputTokens = readRequiredInt(usage.path("completion_tokens"));
            String stopReason = readRequiredText(choice.path("finish_reason"));
            return new AiCompletionResult(text, inputTokens, outputTokens, stopReason);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private static String requireAzureHost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new AiProviderException("Invalid Azure OpenAI endpoint");
        }
        try {
            String host = URI.create(endpoint.trim()).getHost();
            if (host == null || host.isBlank()) {
                throw new AiProviderException("Invalid Azure OpenAI endpoint");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!normalizedHost.endsWith(AZURE_HOST_SUFFIX)) {
                throw new AiProviderException("Invalid Azure OpenAI endpoint");
            }
            return normalizedHost;
        } catch (IllegalArgumentException exception) {
            throw new AiProviderException("Invalid Azure OpenAI endpoint");
        }
    }

    private static String requirePattern(String value, Pattern pattern, String error) {
        if (value == null || value.isBlank() || !pattern.matcher(value).matches()) {
            throw new AiProviderException(error);
        }
        return value;
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

    private static int readRequiredInt(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalidResponse();
        }
        int value = node.intValue();
        if (value < 0) {
            throw invalidResponse();
        }
        return value;
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("Azure OpenAI response was invalid");
    }
}
