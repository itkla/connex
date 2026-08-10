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
        if (output == null || !output.isObject() || !exactFields(output, TOP_LEVEL_FIELDS)) {
            return false;
        }
        JsonNode tool = output.get("tool");
        JsonNode finalAnswer = output.get("final");
        boolean hasTool = tool != null && !tool.isNull();
        boolean hasFinal = finalAnswer != null && !finalAnswer.isNull();
        if (hasTool == hasFinal) {
            return false;
        }
        return hasTool ? permitsTool(tool) : permitsFinal(finalAnswer);
    }

    private boolean permitsTool(JsonNode tool) {
        if (!tool.isObject() || !exactFields(tool, TOOL_FIELDS)) {
            return false;
        }
        JsonNode name = tool.get("name");
        JsonNode args = tool.get("args");
        return name != null && name.isString()
                && toolCatalog.isKnown(name.asString())
                && toolCatalog.permitsArguments(name.asString(), args);
    }

    private static boolean permitsFinal(JsonNode finalAnswer) {
        if (!finalAnswer.isObject() || !exactFields(finalAnswer, FINAL_FIELDS)) {
            return false;
        }
        JsonNode text = finalAnswer.get("text");
        JsonNode citations = finalAnswer.get("citations");
        if (text == null || !text.isString() || text.asString().isBlank()
                || text.asString().length() > MAX_FINAL_CHARS
                || citations == null || !citations.isArray()
                || citations.size() > MAX_CITATIONS) {
            return false;
        }
        for (JsonNode citation : citations) {
            if (!citation.isString() || !HANDLE.matcher(citation.asString()).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        Collection<String> actual = node.propertyNames();
        return actual.size() == expected.size() && actual.containsAll(expected);
    }
}
