package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** Closed provider-neutral JSON Schema supplied for a structured completion. */
public record AiResponseSchema(String name, JsonNode schema) {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public AiResponseSchema {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("AI response schema name is invalid");
        }
        JsonNode source = Objects.requireNonNull(schema, "schema");
        if (!source.isObject()) {
            throw new IllegalArgumentException("AI response schema must be an object");
        }
        schema = source.deepCopy();
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
    }

    @Override
    public String toString() {
        return "AiResponseSchema[name=" + name + ", schema=<redacted>]";
    }
}
