package ooo.klae.connex.backend.ai.provider.vertex;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiImageInputSupport;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Vertex AI adapter for Gemini and Anthropic Claude publisher models. The adapter validates
 * every target component, constructs the regional host and encoded model path, translates Connex
 * messages into the selected model family's JSON, and normalizes provider responses.
 */
@Service
@RequiredArgsConstructor
public class VertexAdapter implements AiProvider {
    private static final String PROVIDER_VERTEX = "vertex";
    private static final String ANTHROPIC_VERSION = "vertex-2023-10-16";
    private static final Pattern VERTEX_PROJECT_ID = Pattern.compile("^[a-z][a-z0-9-]{4,28}[a-z0-9]$");
    private static final Pattern VERTEX_REGION = Pattern.compile("^[a-z]+-[a-z]+[0-9]{1,2}$");
    private static final Pattern VERTEX_MODEL_ID = Pattern.compile("^[a-z0-9._@\\-]{1,128}$");

    private final VertexClient vertexClient;
    private final GoogleAccessTokenClient googleAccessTokenClient;
    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;

    @Override
    public String providerId() {
        return PROVIDER_VERTEX;
    }

    @Override
    public AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target) {
        return target != null && target.modelId() != null && target.modelId().startsWith("gemini")
                ? AiStructuredOutputEnforcement.JSON_SCHEMA
                : AiStructuredOutputEnforcement.PROMPT_ONLY;
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiRequestDeadline deadline = AiRequestDeadline.afterMillis(aiProperties.getRequestTimeoutMs());
        AiProviderTarget target = request.target();
        if (!PROVIDER_VERTEX.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        try {
            String projectId = requirePattern(target.projectId(), VERTEX_PROJECT_ID,
                    "Invalid Vertex project id");
            String region = requirePattern(target.region(), VERTEX_REGION, "Invalid Vertex region");
            String modelId = requireModelId(target.modelId());
            ModelFamily family = modelFamily(modelId);
            if (!request.images().isEmpty()
                    && !AiImageInputSupport.supports(PROVIDER_VERTEX, modelId, region)) {
                throw new AiProviderException("Vertex model does not support image input in this region");
            }
            URI endpoint = endpoint(projectId, region, modelId, family);
            AiStructuredOutputEnforcement enforcement = requestedEnforcement(request, family);
            String requestBody = switch (family) {
                case GEMINI -> buildGeminiRequest(request, enforcement);
                case CLAUDE -> buildClaudeRequest(request);
            };
            String responseBody = request.providerAttemptExecutor().execute(() -> {
                String accessToken = googleAccessTokenClient.accessToken(
                        request.credentials(), deadline);
                return vertexClient.complete(endpoint, accessToken, requestBody, deadline);
            });
            return switch (family) {
                case GEMINI -> parseGeminiResponse(responseBody, enforcement);
                case CLAUDE -> parseClaudeResponse(responseBody, enforcement);
            };
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("Vertex adapter failed");
        }
    }

    private URI endpoint(String projectId, String region, String modelId, ModelFamily family) {
        String host = region + "-aiplatform.googleapis.com";
        String publisher = family == ModelFamily.GEMINI ? "google" : "anthropic";
        String operation = family == ModelFamily.GEMINI ? ":generateContent" : ":rawPredict";
        return URI.create("https://" + host
                + "/v1/projects/" + encodePathSegment(projectId)
                + "/locations/" + encodePathSegment(region)
                + "/publishers/" + publisher
                + "/models/" + encodePathSegment(modelId)
                + operation);
    }

    private String buildGeminiRequest(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode content = contents.addObject();
            content.put("role", geminiRole(message.role()));
            ArrayNode parts = content.putArray("parts");
            if (imagesPending && "user".equals(message.role())) {
                for (AiInputImage image : request.images()) {
                    ObjectNode inlineData = parts.addObject().putObject("inlineData");
                    inlineData.put("mimeType", image.contentType());
                    inlineData.put("data", Base64.getEncoder().encodeToString(image.content()));
                }
                imagesPending = false;
            }
            parts.addObject().put("text", message.content());
        }
        if (imagesPending) {
            throw new AiProviderException("AI images require a user message");
        }
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", request.systemPrompt());
        }
        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", request.maxTokens());
        generationConfig.put("temperature", request.temperature());
        if (enforcement != AiStructuredOutputEnforcement.PROMPT_ONLY) {
            generationConfig.put("responseMimeType", "application/json");
        }
        if (enforcement == AiStructuredOutputEnforcement.JSON_SCHEMA) {
            if (request.responseSchema() == null) {
                throw new AiProviderException("AI response schema is required");
            }
            generationConfig.set("responseJsonSchema", request.responseSchema().schema());
        }
        return objectMapper.writeValueAsString(root);
    }

    private String buildClaudeRequest(AiCompletionRequest request) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("anthropic_version", ANTHROPIC_VERSION);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.put("system", request.systemPrompt());
        }
        ArrayNode messages = root.putArray("messages");
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", claudeRole(message.role()));
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
        root.put("max_tokens", request.maxTokens());
        root.put("temperature", request.temperature());
        return objectMapper.writeValueAsString(root);
    }

    private AiCompletionResult parseGeminiResponse(
            String responseBody,
            AiStructuredOutputEnforcement enforcement) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty() || !candidates.path(0).isObject()) {
                throw invalidResponse();
            }
            JsonNode candidate = candidates.path(0);
            JsonNode parts = candidate.path("content").path("parts");
            String text = concatenateGeminiText(parts);
            JsonNode usage = root.path("usageMetadata");
            if (!usage.isObject()) {
                throw invalidResponse();
            }
            int inputTokens = readRequiredInt(usage.path("promptTokenCount"));
            int outputTokens = readRequiredInt(usage.path("candidatesTokenCount"));
            String stopReason = readRequiredText(candidate.path("finishReason"));
            return new AiCompletionResult(
                    text, inputTokens, outputTokens, stopReason, enforcement);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private AiCompletionResult parseClaudeResponse(
            String responseBody,
            AiStructuredOutputEnforcement enforcement) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            String text = concatenateClaudeText(root.path("content"));
            JsonNode usage = root.path("usage");
            if (!usage.isObject()) {
                throw invalidResponse();
            }
            int inputTokens = readRequiredInt(usage.path("input_tokens"));
            int outputTokens = readRequiredInt(usage.path("output_tokens"));
            String stopReason = readRequiredText(root.path("stop_reason"));
            return new AiCompletionResult(
                    text, inputTokens, outputTokens, stopReason, enforcement);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private static String concatenateGeminiText(JsonNode parts) {
        if (!parts.isArray()) {
            throw invalidResponse();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode textNode = part.path("text");
            if (textNode.isString()) {
                text.append(textNode.asString());
            }
        }
        if (text.isEmpty()) {
            throw invalidResponse();
        }
        return text.toString();
    }

    private static String concatenateClaudeText(JsonNode content) {
        if (!content.isArray()) {
            throw invalidResponse();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asString(null);
            JsonNode textNode = block.path("text");
            if ((type == null || "text".equals(type)) && textNode.isString()) {
                text.append(textNode.asString());
            }
        }
        if (text.isEmpty()) {
            throw invalidResponse();
        }
        return text.toString();
    }

    private static String geminiRole(String role) {
        return switch (role) {
            case "user" -> "user";
            case "assistant" -> "model";
            default -> throw new AiProviderException("Invalid Vertex message role");
        };
    }

    private static String claudeRole(String role) {
        return switch (role) {
            case "user", "assistant" -> role;
            default -> throw new AiProviderException("Invalid Vertex message role");
        };
    }

    private static ModelFamily modelFamily(String modelId) {
        if (modelId.startsWith("gemini")) {
            return ModelFamily.GEMINI;
        }
        if (modelId.startsWith("claude")) {
            return ModelFamily.CLAUDE;
        }
        throw new AiProviderException("Unsupported Vertex model");
    }

    private static AiStructuredOutputEnforcement requestedEnforcement(
            AiCompletionRequest request,
            ModelFamily family) {
        if (family != ModelFamily.GEMINI || request.outputMode() != AiOutputMode.JSON) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        return request.responseSchema() == null
                ? AiStructuredOutputEnforcement.JSON_OBJECT
                : AiStructuredOutputEnforcement.JSON_SCHEMA;
    }

    private static String requireModelId(String modelId) {
        String value = requirePattern(modelId, VERTEX_MODEL_ID, "Invalid Vertex model id");
        if (value.contains("..")) {
            throw new AiProviderException("Invalid Vertex model id");
        }
        return value;
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

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int current = raw & 0xFF;
            if (isUnreserved(current)) {
                encoded.append((char) current);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((current >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(current & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("Vertex response was invalid");
    }

    private enum ModelFamily {
        GEMINI,
        CLAUDE
    }
}
