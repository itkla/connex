package ooo.klae.connex.backend.ai.provider.openai;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiModelCatalog;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolCall;
import ooo.klae.connex.backend.ai.provider.AiToolCallingMode;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
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
    private final AiProperties aiProperties;

    @Override
    public String providerId() {
        return PROVIDER_OPENAI_COMPATIBLE;
    }

    @Override
    public AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target) {
        return AiStructuredOutputEnforcement.JSON_SCHEMA;
    }

    @Override
    public AiReasoningMode reasoningCapability(AiProviderTarget target) {
        return AiReasoningMode.TAGGED;
    }

    @Override
    public AiReasoningMode nativeToolReasoningCapability(AiProviderTarget target) {
        return AiReasoningMode.NATIVE;
    }

    /**
     * Resolves the declared context window for the configured OpenAI-compatible model.
     *
     * <p>An OpenAI-compatible endpoint may serve any model under any name, so an unrecognized id
     * keeps the conservative fallback and {@code connex.ai.model-overrides} is the way an operator
     * declares a self-hosted model's real limits.
     *
     * @see AiModelCatalog
     */
    @Override
    public int contextWindowTokens(AiProviderTarget target) {
        return AiModelCatalog.contextWindowTokens(
                AiModelCatalog.Family.OPENAI_COMPATIBLE, target, aiProperties.getModelOverrides());
    }

    /**
     * Whether this configured endpoint accepts a streamed request.
     *
     * <p>Declared by an operator per model rather than assumed: this adapter serves any
     * OpenAI-compatible endpoint, and one that rejects the streamed request fails the turn outright
     * instead of falling back, so an unverified endpoint is not streamed.
     *
     * @see AiModelCatalog#streamingDeclared
     */
    /**
     * Resolves the declared output ceiling for the configured OpenAI-compatible model.
     * @see AiModelCatalog
     */
    @Override
    public int maxOutputTokens(AiProviderTarget target) {
        return AiModelCatalog.maxOutputTokens(
                AiModelCatalog.Family.OPENAI_COMPATIBLE, target, aiProperties.getModelOverrides());
    }

    @Override
    public AiToolCallingMode toolCallingCapability(AiProviderTarget target) {
        return AiToolCallingMode.NATIVE_FUNCTIONS;
    }

    /**
     * Whether this configured endpoint accepts a streamed request.
     *
     * <p>Declared by an operator per model rather than assumed: this adapter serves any
     * OpenAI-compatible endpoint, and one that rejects the streamed request fails the turn outright
     * rather than falling back to a whole response, so an unverified endpoint is not streamed.
     *
     * @see AiModelCatalog#streamingDeclared
     */
    @Override
    public boolean supportsStreaming(AiProviderTarget target) {
        return AiModelCatalog.streamingDeclared(target, aiProperties.getModelOverrides());
    }

    @Override
    public AiCompletionResult complete(AiCompletionRequest request) {
        if (request == null) {
            throw new AiProviderException("AI completion request is required");
        }
        AiRequestDeadline deadline = request.providerAttemptExecutor()
                .deadline(aiProperties.getRequestTimeoutMs());
        AiProviderTarget target = request.target();
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        try {
            URI endpoint = buildCompletionEndpoint(target);
            AiStructuredOutputEnforcement enforcement = requestedEnforcement(request);
            while (true) {
                try {
                    String requestBody = buildRequestBody(request, enforcement);
                    String responseBody = request.providerAttemptExecutor().execute(() ->
                            openAiCompatibleClient.complete(
                                    endpoint, target.allowInternalEndpoint(),
                                    request.credentials(), requestBody, deadline));
                    return parseResponse(responseBody, enforcement, request.reasoningMode());
                } catch (AiProviderRequestRejectedException exception) {
                    if (enforcement == AiStructuredOutputEnforcement.PROMPT_ONLY
                            || !exception.permitsStructuredOutputFallback()) {
                        throw exception;
                    }
                    enforcement = enforcement.degrade();
                }
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("OpenAI-compatible adapter failed");
        }
    }

    @Override
    public AiCompletionResult completeStreaming(
            AiCompletionRequest request,
            AiProviderStreamObserver observer) {
        if (request == null || observer == null) {
            throw new AiProviderException("AI streaming completion request is required");
        }
        AiRequestDeadline deadline = request.providerAttemptExecutor()
                .deadline(aiProperties.getRequestTimeoutMs());
        AiProviderTarget target = request.target();
        if (!PROVIDER_OPENAI_COMPATIBLE.equals(target.provider())) {
            throw new AiProviderException("Unsupported AI provider");
        }
        try {
            URI endpoint = buildCompletionEndpoint(target);
            AiStructuredOutputEnforcement enforcement = requestedEnforcement(request);
            while (true) {
                try {
                    String requestBody = buildRequestBody(request, enforcement, true);
                    AiStructuredOutputEnforcement appliedEnforcement = enforcement;
                    return request.providerAttemptExecutor().executeStream(() ->
                            openAiCompatibleClient.stream(
                                    endpoint,
                                    target.allowInternalEndpoint(),
                                    request.credentials(),
                                    requestBody,
                                    deadline,
                                    new OpenAiSseAccumulator(
                                            objectMapper,
                                            observer,
                                            appliedEnforcement,
                                            request.reasoningMode())));
                } catch (AiProviderRequestRejectedException exception) {
                    if (enforcement == AiStructuredOutputEnforcement.PROMPT_ONLY
                            || !exception.permitsStructuredOutputFallback()) {
                        throw exception;
                    }
                    enforcement = enforcement.degrade();
                }
            }
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

    private String buildRequestBody(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement) throws Exception {
        return buildRequestBody(request, enforcement, false);
    }

    private String buildRequestBody(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement,
            boolean streamed) throws Exception {
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
        addNativeHistory(messages, request.nativeTools());
        if (imagesPending) {
            throw new AiProviderException("AI images require a user message");
        }
        if (OpenAiChatParameters.usesReasoningDialect(modelId)) {
            root.put("max_completion_tokens", request.maxTokens());
        } else {
            root.put("max_tokens", request.maxTokens());
            root.put("temperature", request.temperature());
        }
        addResponseFormat(root, request, enforcement);
        addNativeTools(root, request.nativeTools());
        if (streamed) {
            root.put("stream", true);
            root.putObject("stream_options").put("include_usage", true);
        }
        return objectMapper.writeValueAsString(root);
    }

    private static void addNativeHistory(
            ArrayNode messages,
            AiNativeToolRequest nativeTools) {
        if (nativeTools == null) {
            return;
        }
        for (AiToolExchange exchange : nativeTools.exchanges()) {
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.putNull("content");
            ObjectNode call = assistant.putArray("tool_calls").addObject();
            call.put("id", exchange.call().id());
            call.put("type", "function");
            if (exchange.call().thoughtSignature() != null) {
                call.putObject("extra_content")
                        .putObject("google")
                        .put("thought_signature", exchange.call().thoughtSignature());
            }
            ObjectNode function = call.putObject("function");
            function.put("name", exchange.call().name());
            function.put("arguments", exchange.call().arguments());
            ObjectNode tool = messages.addObject();
            tool.put("role", "tool");
            tool.put("tool_call_id", exchange.call().id());
            tool.put("content", exchange.maskedResult());
        }
        if (nativeTools.repairMessage() != null) {
            ObjectNode repair = messages.addObject();
            repair.put("role", "user");
            repair.put("content", nativeTools.repairMessage());
        }
    }

    private static void addNativeTools(
            ObjectNode root,
            AiNativeToolRequest nativeTools) {
        if (nativeTools == null) {
            return;
        }
        ArrayNode tools = root.putArray("tools");
        for (AiToolDefinition definition : nativeTools.definitions()) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.put("strict", true);
            function.set("parameters", definition.parametersSchema());
        }
        root.put("tool_choice", nativeTools.finalOnly() ? "none" : "auto");
        root.put("parallel_tool_calls", false);
    }

    private static AiStructuredOutputEnforcement requestedEnforcement(
            AiCompletionRequest request) {
        if (request.reasoningMode() == AiReasoningMode.TAGGED) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        if (request.outputMode() != AiOutputMode.JSON) {
            return AiStructuredOutputEnforcement.PROMPT_ONLY;
        }
        return request.responseSchema() == null
                ? AiStructuredOutputEnforcement.JSON_OBJECT
                : AiStructuredOutputEnforcement.JSON_SCHEMA;
    }

    private static void addResponseFormat(
            ObjectNode root,
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement) {
        switch (enforcement) {
            case PROMPT_ONLY -> {
                return;
            }
            case JSON_OBJECT -> root.putObject("response_format").put("type", "json_object");
            case JSON_SCHEMA -> {
                if (request.responseSchema() == null) {
                    throw new AiProviderException("AI response schema is required");
                }
                ObjectNode responseFormat = root.putObject("response_format");
                responseFormat.put("type", "json_schema");
                ObjectNode jsonSchema = responseFormat.putObject("json_schema");
                jsonSchema.put("name", request.responseSchema().name());
                jsonSchema.put("strict", true);
                jsonSchema.set("schema", request.responseSchema().schema());
            }
        }
    }

    private static String dataUrl(AiInputImage image) {
        return "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.content());
    }

    private AiCompletionResult parseResponse(
            String responseBody,
            AiStructuredOutputEnforcement enforcement,
            AiReasoningMode reasoningMode) {
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
            List<AiToolCall> toolCalls = readToolCalls(message.get("tool_calls"));
            String text = readContent(message.get("content"), !toolCalls.isEmpty());
            int inputTokens = 0;
            int outputTokens = 0;
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                if (!usage.isObject()) {
                    throw invalidResponse();
                }
                JsonNode promptTokens = usage.path("prompt_tokens");
                JsonNode completionTokens = usage.path("completion_tokens");
                if (validTokenCount(promptTokens) && validTokenCount(completionTokens)) {
                    inputTokens = promptTokens.intValue();
                    outputTokens = completionTokens.intValue();
                }
            }
            String stopReason = readRequiredText(choice.path("finish_reason"));
            if ((!toolCalls.isEmpty() && !"tool_calls".equals(stopReason))
                    || (toolCalls.isEmpty() && "tool_calls".equals(stopReason))) {
                throw invalidResponse();
            }
            return new AiCompletionResult(
                    text, inputTokens, outputTokens, stopReason, enforcement,
                    readReasoning(message), reasoningMode, toolCalls);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidResponse();
        }
    }

    private static List<AiToolCall> readToolCalls(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalidResponse();
        }
        if (node.isEmpty()) {
            return List.of();
        }
        List<AiToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject() || !"function".equals(readRequiredText(item.path("type")))) {
                throw invalidResponse();
            }
            JsonNode function = item.path("function");
            if (!function.isObject()) {
                throw invalidResponse();
            }
            try {
                calls.add(new AiToolCall(
                        readRequiredText(item.path("id")),
                        readRequiredText(function.path("name")),
                        readRequiredText(function.path("arguments")),
                        readThoughtSignature(item)));
            } catch (IllegalArgumentException exception) {
                throw invalidResponse();
            }
        }
        return List.copyOf(calls);
    }

    private static String readThoughtSignature(JsonNode toolCall) {
        JsonNode google = toolCall.path("extra_content").path("google");
        JsonNode thoughtSignature = google.get("thought_signature");
        if (thoughtSignature == null || thoughtSignature.isNull()) {
            return null;
        }
        if (!thoughtSignature.isString()) {
            throw invalidResponse();
        }
        return thoughtSignature.asString();
    }

    private static String readContent(JsonNode node, boolean toolCallPresent) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (toolCallPresent) {
                return "";
            }
            throw invalidResponse();
        }
        if (!node.isString()) {
            throw invalidResponse();
        }
        String content = node.asString();
        if (content.isEmpty() && !toolCallPresent) {
            throw invalidResponse();
        }
        return content;
    }

    private static String readReasoning(JsonNode message) {
        JsonNode reasoningContent = message.get("reasoning_content");
        JsonNode reasoning = reasoningContent == null || reasoningContent.isNull()
                ? message.get("reasoning")
                : reasoningContent;
        if (reasoning == null || reasoning.isMissingNode() || reasoning.isNull()) {
            return "";
        }
        if (!reasoning.isString()) {
            throw invalidResponse();
        }
        return reasoning.asString();
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

    private static boolean validTokenCount(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0;
    }

    private static AiProviderException invalidEndpoint() {
        return new AiProviderException("Invalid OpenAI-compatible endpoint");
    }

    private static AiProviderException invalidResponse() {
        return new AiProviderException("OpenAI-compatible response was invalid");
    }
}
