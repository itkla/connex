package ooo.klae.connex.backend.ai.assistant;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
            "text", "citations", "suggestions", "title", "blocks", "coverage");
    private static final Set<String> BLOCK_FIELDS = Set.of(
            "kind", "title", "body", "items", "rows", "citations");
    private static final Set<String> ROW_FIELDS = Set.of(
            "label", "value", "detail", "at", "citations");
    private static final Set<String> COVERAGE_FIELDS = Set.of(
            "status", "asOf", "periodStart", "periodEnd", "sources", "exclusions", "truncated");
    static final Set<String> BLOCK_KINDS = Set.of(
            "answer", "fact", "inference", "recommendation", "metric", "list",
            "comparison", "timeline", "draft", "extraction", "diff", "limitation");
    static final Set<String> ROW_BLOCK_KINDS = Set.of(
            "metric", "comparison", "timeline", "diff", "extraction");
    static final Set<String> COVERAGE_STATUSES = Set.of("complete", "partial", "insufficient");
    /**
     * The single source vocabulary shared by declared answer coverage and by the grounded
     * "What I checked" progress trail, so both surfaces name the same category the same way.
     * {@link AiChatProgressService} adds only the two synthetic milestones scope and answer.
     */
    static final Set<String> COVERAGE_SOURCES = Set.of(
            "records", "deals", "activities", "tasks", "notes",
            "files", "metrics", "schedule", "actions", "other");
    static final Set<String> COVERAGE_EXCLUSIONS = Set.of(
            "private_data", "restricted_records", "unavailable_sources", "unsupported_context",
            "bounded_results", "tool_failure");
    private static final Pattern HANDLE = Pattern.compile("r[1-9][0-9]*");
    private static final Pattern HANDLE_REFERENCE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])r[1-9][0-9]*(?![\\p{L}\\p{N}_])");
    private static final Pattern CONTROL_INSTRUCTION = Pattern.compile(
            "ignore\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions?"
                    + "|system\\s+prompt|developer\\s+(?:message|instructions?)"
                    + "|tool\\s+(?:call|command)|crm_data|model_output|step\\s+schema",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z][1-9][0-9]*)}}");
    /**
     * The calendar shapes a model-declared coverage timestamp may take. Coverage timestamps are
     * echoed verbatim to viewers, including shared-session viewers, so they are constrained to a
     * machine-readable instant rather than accepted as bounded prose.
     */
    private static final List<DateTimeFormatter> COVERAGE_INSTANT_FORMATS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE);
    static final int MAX_COVERAGE_INSTANT_CHARS = 64;
    private static final int MAX_FINAL_CHARS = 16_000;
    private static final int MAX_CITATIONS = 50;
    static final int MAX_BLOCKS = 24;
    static final int MAX_BLOCK_CHARS = 8_000;
    static final int MAX_BLOCK_ITEMS = 20;
    static final int MAX_BLOCK_ITEM_CHARS = 1_000;
    static final int MAX_BLOCK_CITATIONS = 20;
    static final int MAX_ROW_LABEL_CHARS = 120;
    static final int MAX_ROW_VALUE_CHARS = 200;
    static final int MAX_ROW_AT_CHARS = 64;
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
        String blockRejection = blockRejection(finalAnswer.get("blocks"), citedHandles);
        if (blockRejection != null) {
            return blockRejection;
        }
        if (AiAssistantAnswerText.render(finalAnswer.get("blocks")).length() > MAX_FINAL_CHARS) {
            return "final_blocks";
        }
        return coverageRejection(finalAnswer.get("coverage"), citedHandles);
    }

    private static String blockRejection(JsonNode blocks, Set<String> citedHandles) {
        if (blocks == null || !blocks.isArray()
                || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            return "final_blocks";
        }
        for (JsonNode block : blocks) {
            if (!block.isObject() || !exactFields(block, BLOCK_FIELDS)) {
                return "final_blocks";
            }
            JsonNode kind = block.get("kind");
            JsonNode title = block.get("title");
            JsonNode body = block.get("body");
            JsonNode items = block.get("items");
            JsonNode rows = block.get("rows");
            JsonNode citations = block.get("citations");
            if (kind == null || !kind.isString() || !BLOCK_KINDS.contains(kind.asString())
                    || !isNullableText(title, 200)
                    || !isNullableText(body, MAX_BLOCK_CHARS)
                    || items == null || !items.isArray() || items.size() > MAX_BLOCK_ITEMS
                    || rows == null || !rows.isArray() || rows.size() > MAX_BLOCK_ITEMS
                    || citations == null || !citations.isArray()
                    || citations.size() > MAX_BLOCK_CITATIONS) {
                return "final_blocks";
            }
            if (!rows.isEmpty() && !ROW_BLOCK_KINDS.contains(kind.asString())) {
                return "final_blocks";
            }
            if ((body == null || body.isNull()) && items.isEmpty() && rows.isEmpty()) {
                return "final_blocks";
            }
            for (JsonNode item : items) {
                if (!isText(item, MAX_BLOCK_ITEM_CHARS)) {
                    return "final_blocks";
                }
            }
            Set<String> blockHandles = new LinkedHashSet<>();
            for (JsonNode citation : citations) {
                if (!citation.isString() || !HANDLE.matcher(citation.asString()).matches()
                        || !citedHandles.contains(citation.asString())
                        || !blockHandles.add(citation.asString())) {
                    return "final_blocks";
                }
            }
            Set<String> referencedHandles = new LinkedHashSet<>();
            addReferencedHandles(referencedHandles, title);
            addReferencedHandles(referencedHandles, body);
            for (JsonNode item : items) {
                addReferencedHandles(referencedHandles, item);
            }
            String rowRejection = rowRejection(rows, citedHandles, blockHandles, referencedHandles);
            if (rowRejection != null) {
                return rowRejection;
            }
            if (!blockHandles.containsAll(referencedHandles)) {
                return "final_blocks";
            }
        }
        return null;
    }

    private static String rowRejection(
            JsonNode rows,
            Set<String> citedHandles,
            Set<String> blockHandles,
            Set<String> referencedHandles) {
        for (JsonNode row : rows) {
            if (!row.isObject() || !exactFields(row, ROW_FIELDS)) {
                return "final_rows";
            }
            JsonNode label = row.get("label");
            JsonNode value = row.get("value");
            JsonNode detail = row.get("detail");
            JsonNode at = row.get("at");
            JsonNode citations = row.get("citations");
            if (!isText(label, MAX_ROW_LABEL_CHARS)
                    || !isNullableText(value, MAX_ROW_VALUE_CHARS)
                    || !isNullableText(detail, MAX_ROW_VALUE_CHARS)
                    || !isNullableText(at, MAX_ROW_AT_CHARS)
                    || citations == null || !citations.isArray()
                    || citations.size() > MAX_BLOCK_CITATIONS) {
                return "final_rows";
            }
            Set<String> rowHandles = new LinkedHashSet<>();
            for (JsonNode citation : citations) {
                if (!citation.isString() || !HANDLE.matcher(citation.asString()).matches()
                        || !citedHandles.contains(citation.asString())
                        || !rowHandles.add(citation.asString())) {
                    return "final_rows";
                }
            }
            blockHandles.addAll(rowHandles);
            addReferencedHandles(referencedHandles, label);
            addReferencedHandles(referencedHandles, value);
            addReferencedHandles(referencedHandles, detail);
            addReferencedHandles(referencedHandles, at);
        }
        return null;
    }

    private static String coverageRejection(JsonNode coverage, Set<String> citedHandles) {
        if (coverage == null || !coverage.isObject() || !exactFields(coverage, COVERAGE_FIELDS)) {
            return "final_coverage";
        }
        JsonNode status = coverage.get("status");
        JsonNode sources = coverage.get("sources");
        JsonNode exclusions = coverage.get("exclusions");
        JsonNode truncated = coverage.get("truncated");
        if (status == null || !status.isString() || !COVERAGE_STATUSES.contains(status.asString())
                || !isNullableCoverageInstant(coverage.get("asOf"))
                || !isNullableCoverageInstant(coverage.get("periodStart"))
                || !isNullableCoverageInstant(coverage.get("periodEnd"))
                || sources == null || !sources.isArray()
                || exclusions == null || !exclusions.isArray()
                || truncated == null || !truncated.isBoolean()
                || !isUniqueEnumArray(sources, COVERAGE_SOURCES)
                || !isUniqueEnumArray(exclusions, COVERAGE_EXCLUSIONS)) {
            return "final_coverage";
        }
        if ("complete".equals(status.asString())
                && (truncated.asBoolean() || !exclusions.isEmpty())) {
            return "final_coverage";
        }
        Set<String> referencedHandles = new LinkedHashSet<>();
        addReferencedHandles(referencedHandles, coverage.get("asOf"));
        addReferencedHandles(referencedHandles, coverage.get("periodStart"));
        addReferencedHandles(referencedHandles, coverage.get("periodEnd"));
        return citedHandles.containsAll(referencedHandles) ? null : "final_coverage";
    }

    private static boolean isNullableCoverageInstant(JsonNode value) {
        if (value == null) {
            return false;
        }
        return value.isNull()
                || (value.isString() && isCoverageInstant(value.asString()));
    }

    /**
     * Whether a coverage timestamp is a bounded ISO-8601 calendar value rather than model prose.
     *
     * <p>Shared with {@link AiChatCitationProjector}, which revalidates stored documents on read
     * and must reach the same verdict without depending on this guard having run.
     * @param value candidate coverage timestamp
     * @return whether the value is an ISO-8601 date, local date-time, or offset date-time
     */
    static boolean isCoverageInstant(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_COVERAGE_INSTANT_CHARS) {
            return false;
        }
        for (DateTimeFormatter format : COVERAGE_INSTANT_FORMATS) {
            try {
                format.parse(value);
                return true;
            } catch (DateTimeParseException exception) {
                continue;
            }
        }
        return false;
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
