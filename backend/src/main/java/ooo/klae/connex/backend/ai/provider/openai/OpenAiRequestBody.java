package ooo.klae.connex.backend.ai.provider.openai;

import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.SERVER_STATIC;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.PROVIDER_AUTHORED;
import static ooo.klae.connex.backend.ai.masking.OutboundSegment.Origin.PROVIDER_OPAQUE;

import java.util.Base64;
import java.util.List;

import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderDispatch;
import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.ai.provider.AiMessage;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;
import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import ooo.klae.connex.backend.ai.provider.AiToolExchange;
import ooo.klae.connex.backend.ai.provider.OpenAiChatParameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared final-body serializer for the live and scripted OpenAI-compatible providers. */
public final class OpenAiRequestBody {
    private OpenAiRequestBody() {
    }

    /** Prepares the exact body and validates all text provenance before any send. */
    public static AiProviderDispatch prepare(
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement,
            boolean streamed, ObjectMapper objectMapper) {
        String modelId = request.target().modelId();
        if (modelId == null || modelId.isBlank()) {
            throw new AiProviderException("OpenAI-compatible model id is required");
        }
        AiProviderDispatch.Builder evidence = new AiProviderDispatch.Builder(objectMapper);
        ObjectNode root = objectMapper.createObjectNode();
        evidence.text(root, "model", modelId, SERVER_STATIC);
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            evidence.text(system, "role", "system", SERVER_STATIC);
            evidence.text(system, "content", request.systemPrompt(), SERVER_STATIC);
        }
        boolean imagesPending = !request.images().isEmpty();
        for (AiMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            evidence.text(node, "role", message.role(), SERVER_STATIC);
            if (imagesPending && "user".equals(message.role())) {
                ArrayNode content = node.putArray("content");
                ObjectNode text = content.addObject();
                evidence.text(text, "type", "text", SERVER_STATIC);
                evidence.text(text, "text", message.content(), message.segments());
                for (AiInputImage image : request.images()) {
                    ObjectNode imagePart = content.addObject();
                    evidence.text(imagePart, "type", "image_url", SERVER_STATIC);
                    ObjectNode imageUrl = imagePart.putObject("image_url");
                    evidence.text(imageUrl, "url", dataUrl(image), SERVER_STATIC);
                    evidence.text(imageUrl, "detail", "high", SERVER_STATIC);
                }
                imagesPending = false;
            } else {
                evidence.text(node, "content", message.content(), message.segments());
            }
        }
        addNativeHistory(messages, request.nativeTools(), evidence);
        if (imagesPending) {
            throw new AiProviderException("AI images require a user message");
        }
        if (OpenAiChatParameters.usesReasoningDialect(modelId)) {
            root.put("max_completion_tokens", request.maxTokens());
        } else {
            root.put("max_tokens", request.maxTokens());
            root.put("temperature", request.temperature());
        }
        addResponseFormat(root, request, enforcement, evidence);
        addNativeTools(root, request.nativeTools(), evidence);
        if (request.reasoningMode() == AiReasoningMode.NATIVE) {
            root.putObject("extra_body")
                    .putObject("google")
                    .putObject("thinking_config")
                    .put("include_thoughts", true);
        }
        if (streamed) {
            root.put("stream", true);
            root.putObject("stream_options").put("include_usage", true);
        }
        return evidence.build(root, request.providerAttemptExecutor());
    }

    /**
     * Replays completed exchanges as the assistant and tool messages the endpoint expects.
     *
     * <p>One assistant message per run of exchanges sharing a step, carrying one {@code tool_calls}
     * entry per exchange in that run and followed by their {@code tool} messages in the same order.
     * A step that carried exactly one call therefore serializes to the single-call assistant message
     * this adapter has always written, byte for byte; the request record already refused any
     * grouping that would rebuild a message the model never sent.
     *
     * @param messages the request's message array, appended to in place
     * @param nativeTools the native request whose exchanges are replayed, or null
     */
    private static void addNativeHistory(
            ArrayNode messages,
            AiNativeToolRequest nativeTools, AiProviderDispatch.Builder evidence) {
        if (nativeTools == null) {
            return;
        }
        List<AiToolExchange> exchanges = nativeTools.exchanges();
        int index = 0;
        while (index < exchanges.size()) {
            int runEnd = index;
            while (runEnd + 1 < exchanges.size()
                    && exchanges.get(runEnd + 1).step() == exchanges.get(index).step()) {
                runEnd++;
            }
            ObjectNode assistant = messages.addObject();
            evidence.text(assistant, "role", "assistant", SERVER_STATIC);
            assistant.putNull("content");
            ArrayNode toolCalls = assistant.putArray("tool_calls");
            for (int position = index; position <= runEnd; position++) {
                AiToolExchange exchange = exchanges.get(position);
                ObjectNode call = toolCalls.addObject();
                evidence.text(call, "id", exchange.call().id(), PROVIDER_AUTHORED);
                evidence.text(call, "type", "function", SERVER_STATIC);
                if (exchange.call().thoughtSignature() != null) {
                    evidence.text(call.putObject("extra_content").putObject("google"),
                            "thought_signature", exchange.call().thoughtSignature(), PROVIDER_OPAQUE);
                }
                ObjectNode function = call.putObject("function");
                evidence.text(function, "name", exchange.call().name(), SERVER_STATIC);
                evidence.text(function, "arguments", exchange.call().arguments(), PROVIDER_AUTHORED);
            }
            for (int position = index; position <= runEnd; position++) {
                AiToolExchange exchange = exchanges.get(position);
                ObjectNode tool = messages.addObject();
                evidence.text(tool, "role", "tool", SERVER_STATIC);
                evidence.text(tool, "tool_call_id", exchange.call().id(), PROVIDER_AUTHORED);
                evidence.text(tool, "content", exchange.maskedResult(), exchange.resultSegments());
            }
            index = runEnd + 1;
        }
        if (nativeTools.repairMessage() != null) {
            ObjectNode repair = messages.addObject();
            evidence.text(repair, "role", "user", SERVER_STATIC);
            evidence.text(repair, "content", nativeTools.repairMessage(), nativeTools.repairSegments());
        }
    }

    private static void addNativeTools(
            ObjectNode root,
            AiNativeToolRequest nativeTools, AiProviderDispatch.Builder evidence) {
        if (nativeTools == null) {
            return;
        }
        ArrayNode tools = root.putArray("tools");
        for (AiToolDefinition definition : nativeTools.definitions()) {
            ObjectNode tool = tools.addObject();
            evidence.text(tool, "type", "function", SERVER_STATIC);
            ObjectNode function = tool.putObject("function");
            evidence.text(function, "name", definition.name(), SERVER_STATIC);
            evidence.text(function, "description", definition.description(), SERVER_STATIC);
            function.put("strict", true);
            evidence.schema(function, "parameters", definition.parametersSchema());
        }
        evidence.text(root, "tool_choice", nativeTools.finalOnly() ? "none" : "auto", SERVER_STATIC);
        root.put("parallel_tool_calls", nativeTools.maxParallelCalls() > 1);
    }

    private static void addResponseFormat(
            ObjectNode root,
            AiCompletionRequest request,
            AiStructuredOutputEnforcement enforcement, AiProviderDispatch.Builder evidence) {
        switch (enforcement) {
            case PROMPT_ONLY -> {
                return;
            }
            case JSON_OBJECT -> evidence.text(root.putObject("response_format"), "type", "json_object", SERVER_STATIC);
            case JSON_SCHEMA -> {
                if (request.responseSchema() == null) {
                    throw new AiProviderException("AI response schema is required");
                }
                ObjectNode responseFormat = root.putObject("response_format");
                evidence.text(responseFormat, "type", "json_schema", SERVER_STATIC);
                ObjectNode jsonSchema = responseFormat.putObject("json_schema");
                evidence.text(jsonSchema, "name", request.responseSchema().name(), SERVER_STATIC);
                jsonSchema.put("strict", true);
                evidence.schema(jsonSchema, "schema", request.responseSchema().schema());
            }
        }
    }

    private static String dataUrl(AiInputImage image) {
        return "data:" + image.contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.content());
    }

}
