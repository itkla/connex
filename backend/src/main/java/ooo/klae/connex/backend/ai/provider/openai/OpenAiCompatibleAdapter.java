package ooo.klae.connex.backend.ai.provider.openai;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.egress.AiRequestDeadline;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiCompletionResult;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiOutputMode;
import ooo.klae.connex.backend.ai.provider.AiProvider;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderRequestRejectedException;
import ooo.klae.connex.backend.ai.provider.AiProviderTarget;
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
    private static final int CONSERVATIVE_TOKEN_FALLBACK = 4_096;
    private static final Pattern GEMINI_TEXT_MODEL = Pattern.compile(
            "^gemini-(?:2\\.5|3(?:\\.\\d+)?)-(?:flash|pro)(?:[-.@].*)?$");
    private static final Pattern GEMINI_LEGACY_PRO_MODEL = Pattern.compile(
            "^gemini-1\\.5-pro(?:[-.@].*)?$");
    private static final Pattern GEMINI_LEGACY_FLASH_MODEL = Pattern.compile(
            "^gemini-(?:1\\.5|2\\.0)-flash(?:-(?:8b|lite))?(?:[-.@].*)?$");
    private static final Pattern OPENAI_REASONING_MODEL = Pattern.compile(
            "^o[134](?:-(?:mini|pro))?(?:-\\d{4}-\\d{2}-\\d{2})?$");
    private static final Pattern GPT_FOUR_FIVE_MODEL = Pattern.compile(
            "^gpt-4\\.5(?:-preview)?(?:-\\d{4}-\\d{2}-\\d{2})?$");
    private static final Pattern NON_TEXT_MODEL_VARIANT = Pattern.compile(
            ".*-(?:image|live|tts|audio|native-audio|embedding|exp-image)(?:[-.@].*)?$");
    private static final Pattern GPT_FOUR_O_TEXT_MODEL = Pattern.compile(
            "^gpt-4o(?:-mini)?(?:-\\d{4}-\\d{2}-\\d{2})?$");
    private static final Pattern GPT_FOUR_ONE_TEXT_MODEL = Pattern.compile(
            "^gpt-4\\.1(?:-(?:mini|nano))?(?:-\\d{4}-\\d{2}-\\d{2})?$");
    private static final Pattern GPT_FIVE_CHAT_MODEL = Pattern.compile(
            "^gpt-5(?:\\.[123])?-chat-latest$");
    private static final Pattern GPT_FIVE_400K_MODEL = Pattern.compile(
            "^gpt-5(?:\\.(?:1|2))?(?:-(?:mini|nano|pro))?"
                    + "(?:-\\d{4}-\\d{2}-\\d{2})?$"
                    + "|^gpt-5\\.4-(?:mini|nano)(?:-\\d{4}-\\d{2}-\\d{2})?$");
    private static final Pattern GPT_FIVE_MILLION_MODEL = Pattern.compile(
            "^gpt-5\\.(?:4|5)(?:-pro)?(?:-\\d{4}-\\d{2}-\\d{2})?$"
                    + "|^gpt-5\\.6(?:-(?:sol|terra|luna))?$");

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
     * Resolves documented context windows only for recognizable vendor model families.
     * @see <a href="https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro">Gemini 2.5 limits</a>
     * @see <a href="https://ai.google.dev/gemini-api/docs/gemini-3">Gemini 3 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5">GPT-5 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5-chat-latest">GPT-5 Chat limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5.4">GPT-5.4 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-4.1">GPT-4.1 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-4o">GPT-4o limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/o3">OpenAI o-series limits</a>
     * @see <a href="https://ai.google.dev/gemini-api/docs/models">Gemini 1.5/2.0 limits</a>
     * @see <a href="https://platform.claude.com/docs/en/about-claude/models/overview">Claude model limits</a>
     */
    @Override
    public int contextWindowTokens(AiProviderTarget target) {
        String modelId = normalizedModelId(target);
        if (modelId == null || NON_TEXT_MODEL_VARIANT.matcher(modelId).matches()) {
            return CONSERVATIVE_TOKEN_FALLBACK;
        }
        if (GEMINI_TEXT_MODEL.matcher(modelId).matches()) {
            return 1_048_576;
        }
        if (GEMINI_LEGACY_PRO_MODEL.matcher(modelId).matches()) {
            return 2_097_152;
        }
        if (GEMINI_LEGACY_FLASH_MODEL.matcher(modelId).matches()) {
            return 1_048_576;
        }
        if (OPENAI_REASONING_MODEL.matcher(modelId).matches()) {
            return 200_000;
        }
        if (GPT_FOUR_FIVE_MODEL.matcher(modelId).matches()) {
            return 128_000;
        }
        if (GPT_FOUR_ONE_TEXT_MODEL.matcher(modelId).matches()) {
            return 1_047_576;
        }
        if (GPT_FIVE_CHAT_MODEL.matcher(modelId).matches()) {
            return 128_000;
        }
        if (GPT_FIVE_400K_MODEL.matcher(modelId).matches()) {
            return 400_000;
        }
        if (GPT_FIVE_MILLION_MODEL.matcher(modelId).matches()) {
            return 1_050_000;
        }
        if (GPT_FOUR_O_TEXT_MODEL.matcher(modelId).matches()) {
            return 128_000;
        }
        if (isMillionTokenClaude(modelId)) {
            return 1_000_000;
        }
        if (isRecognizedClaude(modelId)) {
            return 200_000;
        }
        if (modelId.startsWith("gemma-4") || isLargeGemmaThree(modelId)) {
            return 128_000;
        }
        if (modelId.startsWith("gemma-3")) {
            return 32_768;
        }
        return CONSERVATIVE_TOKEN_FALLBACK;
    }

    /**
     * Resolves documented output ceilings only for recognizable vendor model families.
     * @see <a href="https://ai.google.dev/gemini-api/docs/models/gemini-2.5-pro">Gemini 2.5 limits</a>
     * @see <a href="https://ai.google.dev/gemini-api/docs/gemini-3">Gemini 3 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5">GPT-5 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5-chat-latest">GPT-5 Chat limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-5.4">GPT-5.4 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-4.1">GPT-4.1 limits</a>
     * @see <a href="https://developers.openai.com/api/docs/models/gpt-4o">GPT-4o limits</a>
     * @see <a href="https://platform.claude.com/docs/en/about-claude/models/overview">Claude model limits</a>
     * @see <a href="https://ai.google.dev/gemma/docs/core/model_card_3">Gemma 3 model limits</a>
     * @see <a href="https://ai.google.dev/gemma/docs/core/model_card_4">Gemma 4 model limits</a>
     */
    @Override
    public int maxOutputTokens(AiProviderTarget target) {
        String modelId = normalizedModelId(target);
        if (modelId == null || NON_TEXT_MODEL_VARIANT.matcher(modelId).matches()) {
            return CONSERVATIVE_TOKEN_FALLBACK;
        }
        if (GEMINI_TEXT_MODEL.matcher(modelId).matches()) {
            return 65_536;
        }
        if (GEMINI_LEGACY_PRO_MODEL.matcher(modelId).matches()
                || GEMINI_LEGACY_FLASH_MODEL.matcher(modelId).matches()) {
            return 8_192;
        }
        if (OPENAI_REASONING_MODEL.matcher(modelId).matches()) {
            return 100_000;
        }
        if (GPT_FOUR_FIVE_MODEL.matcher(modelId).matches()) {
            return 16_384;
        }
        if (GPT_FIVE_CHAT_MODEL.matcher(modelId).matches()) {
            return 16_384;
        }
        if (GPT_FIVE_400K_MODEL.matcher(modelId).matches()
                || GPT_FIVE_MILLION_MODEL.matcher(modelId).matches()) {
            return 128_000;
        }
        if (GPT_FOUR_ONE_TEXT_MODEL.matcher(modelId).matches()) {
            return 32_768;
        }
        if (GPT_FOUR_O_TEXT_MODEL.matcher(modelId).matches()) {
            return 16_384;
        }
        if (isRecognizedClaude(modelId)) {
            return claudeMaxOutputTokens(modelId);
        }
        return modelId.startsWith("gemma-3") || modelId.startsWith("gemma-4")
                ? contextWindowTokens(target)
                : CONSERVATIVE_TOKEN_FALLBACK;
    }

    @Override
    public AiToolCallingMode toolCallingCapability(AiProviderTarget target) {
        return AiToolCallingMode.NATIVE_FUNCTIONS;
    }

    private static boolean isLargeGemmaThree(String modelId) {
        return modelId.matches("^gemma-3-(4b|12b|27b)(?:[-.@].*)?$");
    }

    private static boolean isMillionTokenClaude(String modelId) {
        return modelId.startsWith("claude-fable-5")
                || modelId.startsWith("claude-mythos-5")
                || modelId.startsWith("claude-opus-5")
                || modelId.startsWith("claude-sonnet-5")
                || modelId.startsWith("claude-mythos-preview")
                || modelId.startsWith("claude-opus-4-6")
                || modelId.startsWith("claude-opus-4-7")
                || modelId.startsWith("claude-opus-4-8")
                || modelId.startsWith("claude-sonnet-4-6");
    }

    private static boolean isRecognizedClaude(String modelId) {
        return modelId.startsWith("claude-3")
                || modelId.startsWith("claude-opus-")
                || modelId.startsWith("claude-sonnet-")
                || modelId.startsWith("claude-haiku-")
                || modelId.startsWith("claude-mythos")
                || modelId.startsWith("claude-fable");
    }

    private static int claudeMaxOutputTokens(String modelId) {
        if (modelId.startsWith("claude-fable-5")
                || modelId.startsWith("claude-mythos-5")
                || modelId.startsWith("claude-opus-5")
                || modelId.startsWith("claude-sonnet-5")
                || modelId.startsWith("claude-opus-4-6")
                || modelId.startsWith("claude-opus-4-7")
                || modelId.startsWith("claude-opus-4-8")) {
            return 131_072;
        }
        if (modelId.startsWith("claude-mythos-preview")
                || modelId.startsWith("claude-sonnet-4")
                || modelId.startsWith("claude-haiku-4")
                || modelId.startsWith("claude-opus-4-5")
                || modelId.startsWith("claude-3-7")) {
            return 65_536;
        }
        if (modelId.startsWith("claude-opus-4")) {
            return 32_768;
        }
        if (modelId.startsWith("claude-3-5")) {
            return 8_192;
        }
        return CONSERVATIVE_TOKEN_FALLBACK;
    }

    private static String normalizedModelId(AiProviderTarget target) {
        if (target == null || target.modelId() == null || target.modelId().isBlank()) {
            return null;
        }
        return unqualifiedModelId(target.modelId().trim().toLowerCase(Locale.ROOT));
    }

    private static String unqualifiedModelId(String modelId) {
        int separator = modelId.lastIndexOf('/');
        return separator < 0 ? modelId : modelId.substring(separator + 1);
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
        root.put("tool_choice", "auto");
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
