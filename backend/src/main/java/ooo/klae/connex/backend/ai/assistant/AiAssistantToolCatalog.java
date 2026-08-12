package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** Additive source of truth for the assistant's tiered tool vocabulary and argument schemas. */
@Component
public class AiAssistantToolCatalog {
    private static final Pattern HANDLE = Pattern.compile("r[1-9][0-9]*");

    /** Supported JSON argument kinds. */
    public enum ArgumentKind { STRING, INTEGER, STRING_LIST }

    /** Safety tier controlling whether a declared tool may execute without human approval. */
    public enum ToolTier { READ, AUTO, CONFIRM }

    /** One closed tool argument definition. */
    public record ArgumentSpec(
            String name,
            ArgumentKind kind,
            boolean required,
            int minimum,
            int maximum,
            Set<String> values) {

        public ArgumentSpec {
            values = Set.copyOf(values);
        }
    }

    /** One stable tool key and its execution availability. */
    public record ToolSpec(
            String name,
            ToolTier tier,
            boolean executable,
            String unavailableReason,
            List<ArgumentSpec> arguments) {

        public ToolSpec {
            arguments = List.copyOf(arguments);
        }
    }

    private static final Map<String, ToolSpec> TOOLS = buildTools();

    /** @return every declared tool in stable catalog order */
    public List<ToolSpec> tools() {
        return List.copyOf(TOOLS.values());
    }

    /** @return whether the key is declared, including reserved replay-stable keys */
    public boolean isKnown(String name) {
        return name != null && TOOLS.containsKey(name);
    }

    /** @return whether the declared key is enabled for execution in this increment */
    public boolean isExecutable(String name) {
        ToolSpec spec = TOOLS.get(name);
        return spec != null && spec.executable();
    }

    /** @return whether the declared key performs a mutation */
    public boolean isWrite(String name) {
        ToolSpec spec = TOOLS.get(name);
        return spec != null && spec.tier() != ToolTier.READ;
    }

    /** @return the safety tier for a declared key, or {@code null} when unknown */
    public ToolTier tier(String name) {
        ToolSpec spec = TOOLS.get(name);
        return spec == null ? null : spec.tier();
    }

    /** @return the stable reserved-tool reason, or {@code null} for executable or unknown keys */
    public String unavailableReason(String name) {
        ToolSpec spec = TOOLS.get(name);
        return spec == null ? null : spec.unavailableReason();
    }

    /** Validates the exact raw JSON argument shape for a declared tool. */
    public boolean permitsArguments(String name, JsonNode args) {
        ToolSpec spec = TOOLS.get(name);
        if (spec == null || args == null || !args.isObject()) {
            return false;
        }
        Set<String> expected = spec.arguments().stream()
                .map(ArgumentSpec::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String property : args.propertyNames()) {
            if (!expected.contains(property)) {
                return false;
            }
        }
        for (ArgumentSpec argument : spec.arguments()) {
            JsonNode value = args.get(argument.name());
            if (value == null || value.isNull()) {
                if (argument.required()) {
                    return false;
                }
                continue;
            }
            if (!permits(argument, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean permits(ArgumentSpec argument, JsonNode value) {
        return switch (argument.kind()) {
            case STRING -> permitsString(argument, value);
            case INTEGER -> value.isIntegralNumber()
                    && value.canConvertToInt()
                    && value.asInt() >= argument.minimum()
                    && value.asInt() <= argument.maximum()
                    && (argument.values().isEmpty()
                            || argument.values().contains(Integer.toString(value.asInt())));
            case STRING_LIST -> permitsStringList(argument, value);
        };
    }

    private static boolean permitsString(ArgumentSpec argument, JsonNode value) {
        if (!value.isString()) {
            return false;
        }
        String text = value.asString();
        if (text.length() < argument.minimum() || text.length() > argument.maximum()) {
            return false;
        }
        if ("handle".equals(argument.name()) && !HANDLE.matcher(text).matches()) {
            return false;
        }
        return argument.values().isEmpty() || argument.values().contains(text);
    }

    private static boolean permitsStringList(ArgumentSpec argument, JsonNode value) {
        if (!value.isArray() || value.size() < argument.minimum() || value.size() > argument.maximum()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isString() || !argument.values().contains(item.asString())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, ToolSpec> buildTools() {
        Map<String, ToolSpec> tools = new LinkedHashMap<>();
        add(tools, executable("search_records",
                string("query", true, 1, 200, Set.of()),
                stringList("kinds", false, 1, 3, Set.of("person", "company", "deal"))));
        add(tools, executable("get_record", handle()));
        add(tools, executable("list_activities", handle(), integer("limit", false, 1, 20)));
        add(tools, executable("list_tasks", handle(), integer("limit", false, 1, 20)));
        add(tools, executable("aggregate_metric",
                string("metric", true, 1, 32, Set.of(
                        "deal_metrics", "deal_kpis", "activity_volume",
                        "task_summary", "warmth_summary")),
                string("currency", false, 1, 8, Set.of()),
                integer("days", false, 30, 365, Set.of("30", "90", "365")),
                string("scope", false, 0, 10, Set.of("", "me", "unassigned"))));
        add(tools, executable("find_schedule_conflicts",
                handle(),
                string("start", true, 1, 80, Set.of()),
                string("end", true, 1, 80, Set.of())));
        add(tools, reservedDealBrief());
        add(tools, auto("create_activity",
                handle(),
                string("type", true, 1, 32, Set.of()),
                string("subject", true, 1, 255, Set.of()),
                string("notes", false, 0, 50_000, Set.of()),
                string("start", true, 1, 80, Set.of()),
                integer("duration_minutes", false, 1, 1_440)));
        add(tools, auto("create_task",
                handle(),
                string("description", true, 1, 1_000, Set.of()),
                string("due_date", false, 0, 32, Set.of())));
        add(tools, auto("create_note",
                handle(),
                string("content", true, 1, 50_000, Set.of()),
                string("title", false, 0, 255, Set.of()),
                string("visibility", false, 1, 9, Set.of("private", "workspace"))));
        add(tools, auto("add_tag",
                handle(),
                string("tag", true, 1, 64, Set.of())));
        add(tools, confirm("change_deal_stage",
                handle(),
                string("stage", true, 1, 128, Set.of())));
        add(tools, confirm("assign_owner",
                handle(),
                string("owner", true, 1, 255, Set.of())));
        return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    /**
     * Reserved until deal briefs can be read without a cache-miss model invocation, which would
     * otherwise nest provider egress inside one assistant step.
     */
    private static ToolSpec reservedDealBrief() {
        return reserved("get_deal_brief", "deal_brief_nested_generation_unavailable", handle());
    }

    private static void add(Map<String, ToolSpec> tools, ToolSpec spec) {
        tools.put(spec.name(), spec);
    }

    private static ToolSpec executable(String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, ToolTier.READ, true, null, List.of(arguments));
    }

    private static ToolSpec auto(String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, ToolTier.AUTO, true, null, List.of(arguments));
    }

    private static ToolSpec confirm(String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, ToolTier.CONFIRM, true, null, List.of(arguments));
    }

    private static ToolSpec reserved(String name, String reason, ArgumentSpec... arguments) {
        return new ToolSpec(name, ToolTier.READ, false, reason, List.of(arguments));
    }

    private static ArgumentSpec handle() {
        return string("handle", true, 2, 16, Set.of());
    }

    private static ArgumentSpec string(
            String name, boolean required, int minimum, int maximum, Set<String> values) {
        return new ArgumentSpec(name, ArgumentKind.STRING, required, minimum, maximum, values);
    }

    private static ArgumentSpec integer(String name, boolean required, int minimum, int maximum) {
        return new ArgumentSpec(name, ArgumentKind.INTEGER, required, minimum, maximum, Set.of());
    }

    private static ArgumentSpec integer(
            String name,
            boolean required,
            int minimum,
            int maximum,
            Set<String> values) {
        return new ArgumentSpec(name, ArgumentKind.INTEGER, required, minimum, maximum, values);
    }

    private static ArgumentSpec stringList(
            String name, boolean required, int minimum, int maximum, Set<String> values) {
        return new ArgumentSpec(name, ArgumentKind.STRING_LIST, required, minimum, maximum, values);
    }
}
