package ooo.klae.connex.backend.ai.assistant;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final Set<String> FINAL_FIELDS = Set.of(
            "text", "citations", "suggestions", "title");
    /**
     * The single source vocabulary shared by declared answer coverage and by the grounded
     * "What I checked" progress trail, so both surfaces name the same category the same way.
     * {@link AiChatProgressService} adds only the two synthetic milestones scope and answer.
     */
    static final Set<String> COVERAGE_SOURCES = Set.of(
            "records", "deals", "activities", "tasks", "notes",
            "files", "metrics", "schedule", "actions", "other");
    private static final Pattern HANDLE = Pattern.compile("r[1-9][0-9]*");
    private static final Pattern HANDLE_REFERENCE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])r[1-9][0-9]*(?![\\p{L}\\p{N}_])");
    private static final Pattern CONTROL_INSTRUCTION = Pattern.compile(
            "ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions?"
                    + "|system\\s+prompt|developer\\s+(?:message|instructions?)"
                    + "|tool\\s+(?:call|command)|crm_data|model_output|step\\s+schema",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z][1-9][0-9]*)}}");
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final int MAX_CITATIONS = 50;
    static final int MAX_SUGGESTIONS = 3;
    static final int MAX_SUGGESTION_CHARS = 160;

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

    /**
     * Creates a raw-output guard that also rejects bare bodies of placeholders issued for the
     * current provider call while continuing to accept their braced forms for demasking.
     * @param issuedPlaceholders canonical issued placeholders such as {@code {{P1}}}
     * @return assistant schema and issued-placeholder guard
     */
    public AiRawOutputGuard forIssuedPlaceholders(Set<String> issuedPlaceholders) {
        Set<String> placeholderBodies = issuedPlaceholderBodies(issuedPlaceholders);
        if (placeholderBodies.isEmpty()) {
            return this;
        }
        return new AiRawOutputGuard() {
            @Override
            public boolean permits(JsonNode output) {
                return rejectionReason(output) == null;
            }

            @Override
            public String rejectionReason(JsonNode output) {
                String schemaRejection = AiAssistantStepGuard.this.rejectionReason(output);
                if (schemaRejection != null) {
                    return schemaRejection;
                }
                return containsBareIssuedPlaceholder(output, placeholderBodies)
                        ? "bare_placeholder"
                        : null;
            }
        };
    }

    /**
     * Creates the terminal-answer-only guard used when tools travel through the native protocol.
     * @param issuedPlaceholders canonical issued placeholders such as {@code {{P1}}}
     * @return final-answer schema and issued-placeholder guard
     */
    public AiRawOutputGuard finalAnswerForIssuedPlaceholders(Set<String> issuedPlaceholders) {
        Set<String> placeholderBodies = issuedPlaceholderBodies(issuedPlaceholders);
        return new AiRawOutputGuard() {
            @Override
            public boolean permits(JsonNode output) {
                return rejectionReason(output) == null;
            }

            @Override
            public String rejectionReason(JsonNode output) {
                String schemaRejection = finalRejection(output);
                if (schemaRejection != null) {
                    return schemaRejection;
                }
                return containsBareIssuedPlaceholder(output, placeholderBodies)
                        ? "bare_placeholder"
                        : null;
            }
        };
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
        if (!finalAnswer.isObject()) {
            return "final_fields";
        }
        if (!exactFields(finalAnswer, FINAL_FIELDS)) {
            return "final_fields";
        }
        JsonNode text = finalAnswer.get("text");
        JsonNode citations = finalAnswer.get("citations");
        JsonNode suggestions = finalAnswer.get("suggestions");
        JsonNode title = finalAnswer.get("title");
        if (text == null || !text.isString() || text.asString().isBlank()
                || text.asString().length() > MAX_FINAL_CHARS
                || citations == null || !citations.isArray()
                || suggestions == null || !suggestions.isArray()
                || !isNullableText(title, 200)) {
            return "final_shape";
        }
        if (citations.size() > MAX_CITATIONS) {
            return "final_citations";
        }
        Set<String> citedHandles = new LinkedHashSet<>();
        for (JsonNode citation : citations) {
            if (!citation.isString() || !HANDLE.matcher(citation.asString()).matches()
                    || !citedHandles.add(citation.asString())) {
                return "final_citations";
            }
        }
        var referencedHandles = HANDLE_REFERENCE.matcher(text.asString()).results()
                .map(result -> result.group())
                .collect(java.util.stream.Collectors.toSet());
        if (!citedHandles.containsAll(referencedHandles)) {
            return "final_citations";
        }
        if (suggestions.size() > MAX_SUGGESTIONS) {
            return "final_suggestions";
        }
        Set<String> uniqueSuggestions = new LinkedHashSet<>();
        for (JsonNode suggestion : suggestions) {
            if (!suggestion.isString()) {
                return "final_suggestions";
            }
            String value = suggestion.asString().strip();
            if (!isSafeSuggestion(value)
                    || !uniqueSuggestions.add(value)) {
                return "final_suggestions";
            }
        }
        return null;
    }

    private static boolean isNullableText(JsonNode value, int maxLength) {
        return value != null && (value.isNull() || isText(value, maxLength));
    }

    private static boolean isText(JsonNode value, int maxLength) {
        return value != null && value.isString()
                && !value.asString().isBlank() && value.asString().length() <= maxLength;
    }

    private static boolean isUniqueEnumArray(JsonNode values, Set<String> allowed) {
        if (values.size() > allowed.size()) {
            return false;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isString() || !allowed.contains(value.asString())
                    || !unique.add(value.asString())) {
                return false;
            }
        }
        return true;
    }

    private static void addReferencedHandles(Set<String> handles, JsonNode value) {
        if (value == null || !value.isString()) {
            return;
        }
        HANDLE_REFERENCE.matcher(value.asString()).results()
                .map(result -> result.group())
                .forEach(handles::add);
    }

    static List<String> filterSuggestions(List<String> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (String suggestion : suggestions) {
            if (suggestion == null) {
                continue;
            }
            String value = suggestion.strip();
            if (!isSafeSuggestion(value)) {
                continue;
            }
            filtered.add(value);
            if (filtered.size() == MAX_SUGGESTIONS) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    private static boolean isSafeSuggestion(String value) {
        return !value.isBlank()
                && value.length() <= MAX_SUGGESTION_CHARS
                && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0
                && !containsHandle(value)
                && !containsControlInstruction(value);
    }

    static boolean containsHandle(String value) {
        return value != null && HANDLE_REFERENCE.matcher(value).find();
    }

    static boolean containsControlInstruction(String value) {
        return value != null && CONTROL_INSTRUCTION.matcher(value).find();
    }

    private static boolean exactFields(JsonNode node, Set<String> expected) {
        Collection<String> actual = node.propertyNames();
        return actual.size() == expected.size() && actual.containsAll(expected);
    }

    private static Set<String> issuedPlaceholderBodies(Set<String> issuedPlaceholders) {
        if (issuedPlaceholders == null || issuedPlaceholders.isEmpty()) {
            return Set.of();
        }
        var bodies = new LinkedHashSet<String>();
        for (String placeholder : issuedPlaceholders) {
            if (placeholder == null) {
                continue;
            }
            var matcher = PLACEHOLDER.matcher(placeholder);
            if (matcher.matches()) {
                bodies.add(matcher.group(1));
            }
        }
        return Set.copyOf(bodies);
    }

    private static boolean containsBareIssuedPlaceholder(JsonNode node, Set<String> placeholderBodies) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isString()) {
            return containsBareIssuedPlaceholder(node.asString(), placeholderBodies);
        }
        for (JsonNode child : node) {
            if (containsBareIssuedPlaceholder(child, placeholderBodies)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBareIssuedPlaceholder(String value, Set<String> placeholderBodies) {
        String withoutBracedPlaceholders = value;
        for (String body : placeholderBodies) {
            withoutBracedPlaceholders = Pattern.compile(
                    "\\{\\{\\s*" + Pattern.quote(body) + "\\s*}}")
                    .matcher(withoutBracedPlaceholders)
                    .replaceAll("");
        }
        for (String body : placeholderBodies) {
            Pattern bare = Pattern.compile(
                    "(?<![\\p{L}\\p{N}_])" + Pattern.quote(body)
                            + "(?![\\p{L}\\p{N}_])");
            if (bare.matcher(withoutBracedPlaceholders).find()) {
                return true;
            }
        }
        return false;
    }
}
