package ooo.klae.connex.backend.ai.assistant;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Closed mission bookkeeping schemas shared by native functions and JSON step envelopes. */
final class AiAgentToolSchema {
    private AiAgentToolSchema() {
    }

    static boolean enabled() {
        var policy = ooo.klae.connex.backend.ai.mission.AiAgentExecutionPolicy.current();
        return policy != null && policy.runtimeContractVersion() == 2;
    }

    static boolean bookkeeping(String name) {
        return "set_todos".equals(name) || "checkpoint_segment".equals(name);
    }

    static ObjectNode arguments(ObjectMapper mapper, String name) {
        ObjectNode root = object(mapper);
        ObjectNode properties = (ObjectNode) root.path("properties");
        properties.set("expectedPlanRevision", decimal(mapper));
        properties.set("expectedCheckpointRevision", decimal(mapper));
        if ("set_todos".equals(name)) {
            ObjectNode item = object(mapper);
            ObjectNode fields = (ObjectNode) item.path("properties");
            fields.set("key", nullable(mapper, key(mapper)));
            fields.set("label", text(mapper, 400));
            fields.set("status", enumeration(mapper, "pending", "active", "done"));
            ObjectNode completion = object(mapper);
            ObjectNode completionFields = (ObjectNode) completion.path("properties");
            completionFields.set("summary", text(mapper, 1000));
            completionFields.set("citations", citations(mapper));
            required(completion);
            fields.set("completion", nullable(mapper, completion));
            required(item);
            ObjectNode items = mapper.createObjectNode().put("type", "array")
                    .put("minItems", 0).put("maxItems", 20);
            items.set("items", item);
            properties.set("items", items);
        } else {
            properties.set("nextPlanItemKey", nullable(mapper, key(mapper)));
            ObjectNode memory = object(mapper);
            ObjectNode fields = (ObjectNode) memory.path("properties");
            fields.set("summary", text(mapper, 8000));
            fields.set("citations", citations(mapper));
            required(memory);
            properties.set("memory", memory);
            properties.set("phase", enumeration(mapper, "reading", "reporting"));
        }
        required(root);
        return root;
    }

    static boolean permits(String name, JsonNode args) {
        if (!exact(args, "expectedPlanRevision", "expectedCheckpointRevision",
                "set_todos".equals(name) ? "items" : "nextPlanItemKey",
                "set_todos".equals(name) ? "" : "memory",
                "set_todos".equals(name) ? "" : "phase")) {
            return false;
        }
        if (!decimal(args.path("expectedPlanRevision"))
                || !decimal(args.path("expectedCheckpointRevision"))) {
            return false;
        }
        if ("set_todos".equals(name)) {
            JsonNode items = args.path("items");
            if (!items.isArray() || items.size() > 20) {
                return false;
            }
            for (JsonNode item : items) {
                if (!exact(item, "key", "label", "status", "completion")
                        || !key(item.path("key")) || !text(item.path("label"), 400)
                        || !Set.of("pending", "active", "done").contains(item.path("status").asString())) {
                    return false;
                }
                JsonNode completion = item.path("completion");
                if (!completion.isNull() && (!exact(completion, "summary", "citations")
                        || !text(completion.path("summary"), 1000)
                        || !citations(completion.path("citations")))) {
                    return false;
                }
            }
            return true;
        }
        return key(args.path("nextPlanItemKey"))
                && exact(args.path("memory"), "summary", "citations")
                && args.path("memory").path("summary").isString()
                && args.path("memory").path("summary").asString().length() <= 8000
                && citations(args.path("memory").path("citations"))
                && Set.of("reading", "reporting").contains(args.path("phase").asString());
    }

    private static boolean exact(JsonNode node, String... fields) {
        Set<String> expected = java.util.Arrays.stream(fields).filter(field -> !field.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return node != null && node.isObject() && node.propertyNames().size() == expected.size()
                && node.propertyNames().containsAll(expected);
    }

    private static boolean decimal(JsonNode node) {
        return node.isString() && node.asString().matches("[1-9][0-9]{0,18}");
    }

    private static boolean key(JsonNode node) {
        return node.isNull() || node.isString() && node.asString().matches("p00(?:0[1-9]|1[0-9]|20)");
    }

    private static boolean text(JsonNode node, int max) {
        return node.isString() && !node.asString().isBlank() && node.asString().length() <= max;
    }

    private static boolean citations(JsonNode node) {
        if (!node.isArray() || node.size() > 64) {
            return false;
        }
        for (JsonNode citation : node) {
            if (!citation.isString() || !citation.asString().matches("r[1-9][0-9]*")) {
                return false;
            }
        }
        return true;
    }

    private static ObjectNode object(ObjectMapper mapper) {
        ObjectNode object = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        object.putObject("properties");
        return object;
    }

    private static void required(ObjectNode node) {
        var required = node.putArray("required");
        node.path("properties").propertyNames().forEach(required::add);
    }

    private static ObjectNode decimal(ObjectMapper mapper) {
        return mapper.createObjectNode().put("type", "string").put("pattern", "^[1-9][0-9]{0,18}$");
    }

    private static ObjectNode key(ObjectMapper mapper) {
        return mapper.createObjectNode().put("type", "string").put("pattern", "^p00(?:0[1-9]|1[0-9]|20)$");
    }

    private static ObjectNode text(ObjectMapper mapper, int maximum) {
        return mapper.createObjectNode().put("type", "string").put("maxLength", maximum);
    }

    private static ObjectNode enumeration(ObjectMapper mapper, String... values) {
        ObjectNode node = mapper.createObjectNode().put("type", "string");
        var options = node.putArray("enum");
        java.util.Arrays.stream(values).forEach(options::add);
        return node;
    }

    private static ObjectNode nullable(ObjectMapper mapper, ObjectNode value) {
        ObjectNode node = mapper.createObjectNode();
        node.putArray("anyOf").add(value).addObject().put("type", "null");
        return node;
    }

    private static ObjectNode citations(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode().put("type", "array").put("maxItems", 64);
        node.putObject("items").put("type", "string").put("pattern", "^r[1-9][0-9]*$");
        return node;
    }
}
