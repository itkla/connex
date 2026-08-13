package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/** Static provider-neutral function definition containing no tenant data. */
public record AiToolDefinition(String name, String description, JsonNode parametersSchema) {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public AiToolDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("AI tool name is invalid");
        }
        if (description == null || description.isBlank() || description.length() > 1_024) {
            throw new IllegalArgumentException("AI tool description is invalid");
        }
        JsonNode source = Objects.requireNonNull(parametersSchema, "parametersSchema");
        if (!source.isObject()) {
            throw new IllegalArgumentException("AI tool parameters schema must be an object");
        }
        parametersSchema = source.deepCopy();
    }

    @Override
    public JsonNode parametersSchema() {
        return parametersSchema.deepCopy();
    }

    @Override
    public String toString() {
        return "AiToolDefinition[name=" + name + ", description=<redacted>, parametersSchema=<redacted>]";
    }
}
