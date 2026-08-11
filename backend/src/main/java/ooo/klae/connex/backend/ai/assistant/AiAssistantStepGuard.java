package ooo.klae.connex.backend.ai.assistant;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import tools.jackson.databind.JsonNode;

/** Raw masked-output guard for the exclusive tool-or-final assistant step schema. */
@Component
@RequiredArgsConstructor
public class AiAssistantStepGuard implements AiRawOutputGuard {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("tool", "final");
    private static final Set<String> TOOL_FIELDS = Set.of("name", "args");
    private static final Set<String> FINAL_FIELDS = Set.of("text", "citations");
    private static final Pattern HANDLE = Pattern.compile("r[1-9][0-9]*");
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final int MAX_CITATIONS = 50;

    private final AiAssistantToolCatalog toolCatalog;

    @Override
    public boolean permits(JsonNode output) {
        return rejectionReason(output) == null;
    }

    @Override
    public String rejectionReason(JsonNode output) {
        if (output == null || !output.isObject() || !exactFields(output, TOP_LEVEL_FIELDS)) {
            return "top_level_fields";
        }
        JsonNode tool = output.get("tool");
        JsonNode finalAnswer = output.get("final");
        boolean hasTool = tool != null && !tool.isNull();
        boolean hasFinal = finalAnswer != null && !finalAnswer.isNull();
        if (hasTool == hasFinal) {
            return "exclusive_step";
        }
        return hasTool ? toolRejection(tool) : finalRejection(finalAnswer);
    }

    private String toolRejection(JsonNode tool) {
        if (!tool.isObject() || !exactFields(tool, TOOL_FIELDS)) {
            return "tool_fields";
        }
        JsonNode name = tool.get("name");
        JsonNode args = tool.get("args");
        if (name == null || !name.isString() || !toolCatalog.isKnown(name.asString())) {
            return "tool_name";
        }
        return toolCatalog.permitsArguments(name.asString(), args)
                ? null
                : "tool_arguments";
    }

    private static String finalRejection(JsonNode finalAnswer) {
        if (!finalAnswer.isObject() || !exactFields(finalAnswer, FINAL_FIELDS)) {
            return "final_fields";
        }
        JsonNode text = finalAnswer.get("text");
        JsonNode citations = finalAnswer.get("citations");
        if (text == null || !text.isString() || text.asString().isBlank()
                || text.asString().length() > MAX_FINAL_CHARS
                || citations == null || !citations.isArray()) {
            return "final_shape";
        }
        if (citations.size() > MAX_CITATIONS) {
            return "final_citations";
        }
        for (JsonNode citation : citations) {
            if (!citation.isString() || !HANDLE.matcher(citation.asString()).matches()) {
                return "final_citations";
            }
        }
        return null;
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        Collection<String> actual = node.propertyNames();
        return actual.size() == expected.size() && actual.containsAll(expected);
    }
}
