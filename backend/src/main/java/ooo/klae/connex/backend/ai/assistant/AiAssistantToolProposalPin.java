package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** Durable proposal-only values, never copied into a model outcome or a withheld review. */
final class AiAssistantToolProposalPin {
    private AiAssistantToolProposalPin() {
    }

    /** Copies exactly the scalar vocabulary supported by inverse metadata. */
    static Map<String, Object> copy(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid();
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                result.put(key, copy(nested));
            } else if (value instanceof String || value instanceof Integer || value instanceof Boolean) {
                result.put(key, value);
            } else {
                throw invalid();
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Reads an optional additive sibling; a present malformed pin is never silently dropped. */
    static Map<String, Object> read(JsonNode root) {
        JsonNode pinned = root.get("pinned");
        return pinned == null ? Map.of() : readMap(pinned);
    }

    private static Map<String, Object> readMap(JsonNode value) {
        if (!value.isObject()) {
            throw invalid();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            JsonNode item = entry.getValue();
            if (item.isObject()) {
                result.put(entry.getKey(), readMap(item));
            } else if (item.isString()) {
                result.put(entry.getKey(), item.asString());
            } else if (item.isIntegralNumber() && item.canConvertToInt()) {
                result.put(entry.getKey(), item.asInt());
            } else if (item.isBoolean()) {
                result.put(entry.getKey(), item.booleanValue());
            } else {
                throw invalid();
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Assistant proposal pin is invalid");
    }
}
