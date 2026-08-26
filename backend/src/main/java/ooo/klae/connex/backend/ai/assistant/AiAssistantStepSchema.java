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
        ObjectNode blocks = properties.putObject("blocks");
        blocks.put("type", "array");
        blocks.put("minItems", 1);
        blocks.put("maxItems", AiAssistantStepGuard.MAX_BLOCKS);
        blocks.set("items", answerBlockSchema(objectMapper));
        properties.set("coverage", coverageSchema(objectMapper));
        answer.putArray("required")
                .add("text")
                .add("citations")
                .add("suggestions")
                .add("title")
                .add("blocks")
                .add("coverage");
        answer.put("additionalProperties", false);
        return answer;
    }

    private static ObjectNode answerBlockSchema(ObjectMapper objectMapper) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "object");
        ObjectNode properties = block.putObject("properties");
        ObjectNode kind = properties.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        AiAssistantStepGuard.BLOCK_KINDS.stream().sorted().forEach(kinds::add);
        properties.set("title", nullableText(objectMapper, 200));
        properties.set("body", nullableText(objectMapper, AiAssistantStepGuard.MAX_BLOCK_CHARS));
        ObjectNode items = properties.putObject("items");
        items.put("type", "array");
        items.put("maxItems", AiAssistantStepGuard.MAX_BLOCK_ITEMS);
        ObjectNode item = items.putObject("items");
        item.put("type", "string");
        item.put("minLength", 1);
        item.put("maxLength", AiAssistantStepGuard.MAX_BLOCK_ITEM_CHARS);
        ObjectNode rows = properties.putObject("rows");
        rows.put("type", "array");
        rows.put("maxItems", AiAssistantStepGuard.MAX_BLOCK_ITEMS);
        rows.set("items", answerRowSchema(objectMapper));
        ObjectNode citations = properties.putObject("citations");
        citations.put("type", "array");
        citations.put("maxItems", AiAssistantStepGuard.MAX_BLOCK_CITATIONS);
        citations.putObject("items").put("type", "string");
        block.putArray("required")
                .add("kind")
                .add("title")
                .add("body")
                .add("items")
                .add("rows")
                .add("citations");
        block.put("additionalProperties", false);
        ArrayNode contentAlternatives = block.putArray("anyOf");
        ObjectNode bodyContent = contentAlternatives.addObject();
        ObjectNode requiredBody = bodyContent.putObject("properties").putObject("body");
        requiredBody.put("type", "string");
        requiredBody.put("minLength", 1);
        ObjectNode itemContent = contentAlternatives.addObject();
        ObjectNode requiredItems = itemContent.putObject("properties").putObject("items");
        requiredItems.put("type", "array");
        requiredItems.put("minItems", 1);
        ObjectNode rowContent = contentAlternatives.addObject();
        ObjectNode requiredRows = rowContent.putObject("properties").putObject("rows");
        requiredRows.put("type", "array");
        requiredRows.put("minItems", 1);
        return block;
    }

    private static ObjectNode answerRowSchema(ObjectMapper objectMapper) {
        ObjectNode row = objectMapper.createObjectNode();
        row.put("type", "object");
        ObjectNode properties = row.putObject("properties");
        ObjectNode label = properties.putObject("label");
        label.put("type", "string");
        label.put("minLength", 1);
        label.put("maxLength", AiAssistantStepGuard.MAX_ROW_LABEL_CHARS);
        properties.set("value", nullableText(objectMapper, AiAssistantStepGuard.MAX_ROW_VALUE_CHARS));
        properties.set(
                "detail", nullableText(objectMapper, AiAssistantStepGuard.MAX_ROW_VALUE_CHARS));
        properties.set("at", nullableText(objectMapper, AiAssistantStepGuard.MAX_ROW_AT_CHARS));
        ObjectNode citations = properties.putObject("citations");
        citations.put("type", "array");
        citations.put("maxItems", AiAssistantStepGuard.MAX_BLOCK_CITATIONS);
        citations.putObject("items").put("type", "string");
        row.putArray("required")
                .add("label")
                .add("value")
                .add("detail")
                .add("at")
                .add("citations");
        row.put("additionalProperties", false);
        return row;
    }

    private static ObjectNode coverageSchema(ObjectMapper objectMapper) {
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("type", "object");
        ObjectNode properties = coverage.putObject("properties");
        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        ArrayNode statuses = status.putArray("enum");
        AiAssistantStepGuard.COVERAGE_STATUSES.stream().sorted().forEach(statuses::add);
        properties.set("asOf", nullableText(objectMapper, 64));
        properties.set("periodStart", nullableText(objectMapper, 64));
        properties.set("periodEnd", nullableText(objectMapper, 64));
        ObjectNode sources = properties.putObject("sources");
        sources.put("type", "array");
        sources.put("maxItems", AiAssistantStepGuard.COVERAGE_SOURCES.size());
        ObjectNode source = sources.putObject("items");
        source.put("type", "string");
        ArrayNode sourceValues = source.putArray("enum");
        AiAssistantStepGuard.COVERAGE_SOURCES.stream().sorted().forEach(sourceValues::add);
        ObjectNode exclusions = properties.putObject("exclusions");
        exclusions.put("type", "array");
        exclusions.put("maxItems", AiAssistantStepGuard.COVERAGE_EXCLUSIONS.size());
        ObjectNode exclusion = exclusions.putObject("items");
        exclusion.put("type", "string");
        ArrayNode exclusionValues = exclusion.putArray("enum");
        AiAssistantStepGuard.COVERAGE_EXCLUSIONS.stream().sorted().forEach(exclusionValues::add);
        properties.putObject("truncated").put("type", "boolean");
        coverage.putArray("required")
                .add("status")
                .add("asOf")
                .add("periodStart")
                .add("periodEnd")
                .add("sources")
                .add("exclusions")
                .add("truncated");
        coverage.put("additionalProperties", false);
        ArrayNode truthfulnessAlternatives = coverage.putArray("anyOf");
        ObjectNode complete = truthfulnessAlternatives.addObject().putObject("properties");
        complete.putObject("status").putArray("enum").add("complete");
        complete.putObject("exclusions").put("maxItems", 0);
        complete.putObject("truncated").putArray("enum").add(false);
        ObjectNode incomplete = truthfulnessAlternatives.addObject().putObject("properties");
        incomplete.putObject("status").putArray("enum").add("partial").add("insufficient");
        return coverage;
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
