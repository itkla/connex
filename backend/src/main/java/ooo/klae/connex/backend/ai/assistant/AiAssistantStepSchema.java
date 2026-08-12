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

    private final AiResponseSchema responseSchema;

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
    }

    /** @return immutable provider-neutral assistant step schema */
    public AiResponseSchema responseSchema() {
        return responseSchema;
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
        properties.putObject("text").put("type", "string");
        ObjectNode citations = properties.putObject("citations");
        citations.put("type", "array");
        citations.putObject("items").put("type", "string");
        answer.putArray("required").add("text").add("citations");
        answer.put("additionalProperties", false);
        return answer;
    }

}
