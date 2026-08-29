package ooo.klae.connex.backend.ai.assistant;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ArgumentSpec;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolSpec;
import ooo.klae.connex.backend.ai.provider.AiResponseSchema;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the provider-neutral strict JSON Schema for one assistant step. */
@Component
public class AiAssistantStepSchema {
    private static final String SCHEMA_NAME = "ask_connex_step";
    private static final String FINAL_SCHEMA_NAME = "ask_connex_final";
    private static final String CLOSING_SCHEMA_NAME = "ask_connex_closing_step";

    private final AiResponseSchema responseSchema;
    private final AiResponseSchema finalResponseSchema;
    private final AiResponseSchema closingResponseSchema;

    public AiAssistantStepSchema(
            ObjectMapper objectMapper,
            AiAssistantToolCatalog toolCatalog) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.set("tool", toolSchema(objectMapper, toolCatalog));
        properties.set("final", finalSchema(objectMapper));
        root.putArray("required").add("tool").add("final");
        root.put("additionalProperties", false);
        responseSchema = new AiResponseSchema(SCHEMA_NAME, root);
        finalResponseSchema = new AiResponseSchema(
                FINAL_SCHEMA_NAME, finalAnswerObjectSchema(objectMapper));
        ObjectNode closingRoot = objectMapper.createObjectNode();
        closingRoot.put("type", "object");
        ObjectNode closingProperties = closingRoot.putObject("properties");
        closingProperties.putObject("tool").put("type", "null");
        closingProperties.set("final", finalAnswerObjectSchema(objectMapper));
        closingRoot.putArray("required").add("tool").add("final");
        closingRoot.put("additionalProperties", false);
        closingResponseSchema = new AiResponseSchema(CLOSING_SCHEMA_NAME, closingRoot);
    }

    /** @return immutable provider-neutral assistant step schema */
    public AiResponseSchema responseSchema() {
        return responseSchema;
    }

    /** @return strict terminal-answer schema used alongside native provider tools */
    public AiResponseSchema finalResponseSchema() {
        return finalResponseSchema;
    }

    /**
     * The closing-step schema: the same step envelope with the tool branch closed to null and the
     * final answer required.
     *
     * <p>The closing step exists to answer from evidence already gathered, but a directive alone
     * did not stop a model from proposing one more tool and forfeiting the whole turn. Making the
     * schema itself refuse a tool step turns that forfeit into a structurally guaranteed answer;
     * the loop's refusal of tools during closing remains only as a backstop for providers that do
     * not enforce the schema.
     *
     * @return immutable closing-step schema that admits only a final answer
     */
    public AiResponseSchema closingResponseSchema() {
        return closingResponseSchema;
    }

    private static ObjectNode toolSchema(
            ObjectMapper objectMapper,
            AiAssistantToolCatalog toolCatalog) {
        ObjectNode tool = objectMapper.createObjectNode();
        ArrayNode alternatives = tool.putArray("anyOf");
        alternatives.addObject().put("type", "null");
        alternatives.add(toolObjectSchema(objectMapper, toolCatalog));
        return tool;
    }

    private static ObjectNode toolObjectSchema(
            ObjectMapper objectMapper,
            AiAssistantToolCatalog toolCatalog) {
        ObjectNode tool = objectMapper.createObjectNode();
        ArrayNode alternatives = tool.putArray("anyOf");
        for (ToolSpec spec : toolCatalog.tools()) {
            ObjectNode branch = alternatives.addObject();
            branch.put("type", "object");
            ObjectNode properties = branch.putObject("properties");
            ObjectNode name = properties.putObject("name");
            name.put("type", "string");
            name.putArray("enum").add(spec.name());
            properties.set("args", argumentsSchema(objectMapper, spec));
            branch.putArray("required").add("name").add("args");
            branch.put("additionalProperties", false);
        }
        return tool;
    }

    private static ObjectNode argumentsSchema(
            ObjectMapper objectMapper,
            ToolSpec tool) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("type", "object");
        ObjectNode properties = args.putObject("properties");
        ArrayNode required = args.putArray("required");
        for (ArgumentSpec argument : tool.arguments()) {
            properties.set(argument.name(), argumentSchema(objectMapper, argument));
            required.add(argument.name());
        }
        args.put("additionalProperties", false);
        return args;
    }

    private static ObjectNode argumentSchema(
            ObjectMapper objectMapper,
            ArgumentSpec argument) {
        ObjectNode value = valueSchema(objectMapper, argument);
        if (argument.required()) {
            return value;
        }
        ObjectNode optional = objectMapper.createObjectNode();
        optional.putArray("anyOf")
                .add(value)
                .addObject()
                .put("type", "null");
        return optional;
    }

    private static ObjectNode valueSchema(
            ObjectMapper objectMapper,
            ArgumentSpec argument) {
        ObjectNode value = objectMapper.createObjectNode();
        switch (argument.kind()) {
            case STRING -> {
                value.put("type", "string");
                addEnum(value, argument);
            }
            case INTEGER -> {
                value.put("type", "integer");
                if (!argument.values().isEmpty()) {
                    ArrayNode allowed = value.putArray("enum");
                    argument.values().stream()
                            .mapToInt(Integer::parseInt)
                            .sorted()
                            .forEach(allowed::add);
                }
            }
            case STRING_LIST -> {
                value.put("type", "array");
                ObjectNode items = value.putObject("items");
                items.put("type", "string");
                addEnum(items, argument);
            }
            case TEXT_LIST -> {
                value.put("type", "array");
                value.put("maxItems", argument.maximum());
                ObjectNode items = value.putObject("items");
                items.put("type", "string");
                items.put("minLength", 1);
                items.put("maxLength", AiAssistantToolCatalog.MAX_TEXT_LIST_ITEM_CHARS);
            }
        }
        return value;
    }

    private static void addEnum(ObjectNode node, ArgumentSpec argument) {
        if (argument.values().isEmpty()) {
            return;
        }
        ArrayNode allowed = node.putArray("enum");
        argument.values().stream().sorted().forEach(allowed::add);
    }

    private static ObjectNode finalSchema(ObjectMapper objectMapper) {
        ObjectNode finalAnswer = objectMapper.createObjectNode();
        ArrayNode alternatives = finalAnswer.putArray("anyOf");
        alternatives.addObject().put("type", "null");
        alternatives.add(finalAnswerObjectSchema(objectMapper));
        return finalAnswer;
    }

    private static ObjectNode finalAnswerObjectSchema(ObjectMapper objectMapper) {
        ObjectNode answer = objectMapper.createObjectNode();
        answer.put("type", "object");
        ObjectNode properties = answer.putObject("properties");
        ObjectNode text = properties.putObject("text");
        text.put("type", "string");
        text.put("maxLength", 16000);
        ObjectNode citations = properties.putObject("citations");
        citations.put("type", "array");
        citations.put("maxItems", 50);
        citations.putObject("items").put("type", "string");
        ObjectNode suggestions = properties.putObject("suggestions");
        suggestions.put("type", "array");
        suggestions.put("maxItems", AiAssistantStepGuard.MAX_SUGGESTIONS);
        ObjectNode suggestion = suggestions.putObject("items");
        suggestion.put("type", "string");
        suggestion.put("minLength", 1);
        suggestion.put("maxLength", AiAssistantStepGuard.MAX_SUGGESTION_CHARS);
        ObjectNode title = properties.putObject("title");
        ArrayNode titleAlternatives = title.putArray("anyOf");
        titleAlternatives.addObject().put("type", "null");
        ObjectNode titleText = titleAlternatives.addObject();
        titleText.put("type", "string");
        titleText.put("maxLength", 200);
        answer.putArray("required")
                .add("text")
                .add("citations")
                .add("suggestions")
                .add("title");
        answer.put("additionalProperties", false);
        return answer;
    }

    private static ObjectNode nullableText(
            ObjectMapper objectMapper, int maxLength) {
        ObjectNode value = objectMapper.createObjectNode();
        ArrayNode alternatives = value.putArray("anyOf");
        alternatives.addObject().put("type", "null");
        ObjectNode text = alternatives.addObject();
        text.put("type", "string");
        text.put("minLength", 1);
        text.put("maxLength", maxLength);
        return value;
    }

}
