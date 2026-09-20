package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.provider.AiToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Additive source of truth for the assistant's tiered tool vocabulary and argument schemas. */
@Component
public class AiAssistantToolCatalog {
    private static final Pattern HANDLE = Pattern.compile("r[1-9][0-9]*");
    /** Longest single free-text list entry, sized for one plan step rather than prose. */
    static final int MAX_TEXT_LIST_ITEM_CHARS = 120;

    /**
     * The meta-tool a turn calls to widen its own vocabulary.
     *
     * <p>It is the one declared-executable tool the agent loop handles itself, because it mutates
     * per-turn state the stateless executor deliberately does not hold.
     */
    public static final String FIND_TOOLS = "find_tools";

    /** Supported JSON argument kinds. */
    public enum ArgumentKind { STRING, INTEGER, STRING_LIST, TEXT_LIST }

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

    /**
     * Named group of declared tools a turn holds together.
     *
     * <p>Grouping is by object family rather than by tier, so a family can grow without dragging
     * every other write tool into the same load. {@link #CORE} is always held; the rest are
     * loadable, and their keys are the stable wire vocabulary.
     */
    public enum Toolset {
        CORE("core", "Record, activity, task, and plan reads always available"),
        ANALYTICS("analytics", "Workspace pipeline, activity, and warmth metric aggregates"),
        SCHEDULE("schedule", "Meeting conflict and availability reads for one record"),
        WRITE_ACTIVITY("write_activity", "Log activities and create tasks on one record"),
        WRITE_CONTENT("write_content", "Write notes and add tags to one record"),
        WRITE_PIPELINE("write_pipeline", "Propose deal stage changes and owner assignments");

        private final String key;
        private final String summary;

        Toolset(String key, String summary) {
            this.key = key;
            this.summary = summary;
        }

        /** @return the stable lowercase wire key for this toolset */
        public String key() {
            return key;
        }

        /** @return the server-authored one-line summary rendered in the toolset directory */
        public String summary() {
            return summary;
        }
    }

    /** The toolset every turn holds from its first step and can never release. */
    public static final Set<Toolset> CORE = Set.of(Toolset.CORE);

    /** Every non-core toolset, in declaration order. */
    public static final List<Toolset> LOADABLE = EnumSet.complementOf(EnumSet.of(Toolset.CORE))
            .stream()
            .toList();

    /** Every declared toolset, the widest vocabulary the catalog can serialize. */
    public static final Set<Toolset> ALL = Collections.unmodifiableSet(
            EnumSet.allOf(Toolset.class));

    /**
     * The total non-core toolsets one turn may ever hold, whether seeded by a routed skill or
     * loaded during the turn.
     *
     * <p>It is one budget rather than two counters, so {@link #reservationToolsets()} is a strict
     * upper bound on the vocabulary any reachable turn can send.
     */
    public static final int MAX_ACTIVE_TOOLSETS_PER_TURN = 2;

    /**
     * Resolves a wire key onto the loadable toolset that declares it.
     *
     * <p>The one lookup every caller that accepts a key uses — a skill declaration, the turn's
     * seed, the {@code find_tools} loader — so no caller can string-match its way to a toolset the
     * catalog does not declare, and {@code core} is never reachable from a key: it is always held
     * and is never something a declaration or a model may ask for.
     *
     * @param key a declared lowercase toolset key
     * @return the loadable toolset with that key, or {@code null} when no declaration owns it
     */
    public static Toolset loadableByKey(String key) {
        for (Toolset toolset : LOADABLE) {
            if (toolset.key().equals(key)) {
                return toolset;
            }
        }
        return null;
    }

    /**
     * Extra loadable toolsets the reservation carries beyond {@link #MAX_ACTIVE_TOOLSETS_PER_TURN}.
     *
     * <p>The weight proxy below is not a byte count, and it cannot be: the two protocols serialize
     * different things. A reserved, non-executable tool costs the JSON-ReAct vocabulary but never
     * reaches the native definitions, and a closed enum costs the proxy one unit per value while
     * costing a prompt only a short string. Measured on the current catalog the two protocols rank
     * the families differently — JSON-ReAct is heaviest on {@code analytics} with
     * {@code write_activity}, native on {@code write_activity} with {@code write_content} — so no
     * single choice of {@link #MAX_ACTIVE_TOOLSETS_PER_TURN} toolsets dominates both. One toolset
     * of headroom does, and {@code AiAssistantPromptEnvelopeTest} measures every reachable
     * combination on both protocols to prove it rather than assume it.
     */
    public static final int RESERVATION_HEADROOM_TOOLSETS = 1;

    /**
     * One stable tool key, its toolset, and its execution availability.
     *
     * <p>The toolset is required: every catalog view filters on it, so a declaration without one
     * would vanish from the vocabulary, the native definitions and {@code isLoaded} for
     * {@link #ALL} while still passing {@code isKnown}, and would throw from
     * {@link #CORE}'s immutable {@code contains}. Failing at class initialisation keeps the
     * partition an invariant rather than a silently enforced filter.
     */
    public record ToolSpec(
            String name,
            Toolset toolset,
            ToolTier tier,
            boolean executable,
            String unavailableReason,
            List<ArgumentSpec> arguments) {

        public ToolSpec {
            Objects.requireNonNull(toolset, name + " declares no toolset");
            arguments = List.copyOf(arguments);
        }
    }

    private static final Map<String, ToolSpec> TOOLS = buildTools();

    /**
     * @param loadedToolsets the toolsets the turn currently holds
     * @return the declared tools of those toolsets in stable catalog order
     */
    public List<ToolSpec> tools(Set<Toolset> loadedToolsets) {
        return TOOLS.values().stream()
                .filter(spec -> loadedToolsets.contains(spec.toolset()))
                .toList();
    }

    /**
     * @param objectMapper mapper building the parameter schemas
     * @param loadedToolsets the toolsets the turn currently holds
     * @return executable native function definitions for those toolsets in stable catalog order
     */
    public List<AiToolDefinition> nativeDefinitions(
            ObjectMapper objectMapper, Set<Toolset> loadedToolsets) {
        return TOOLS.values().stream()
                .filter(ToolSpec::executable)
                .filter(spec -> loadedToolsets.contains(spec.toolset()))
                .map(spec -> new AiToolDefinition(
                        spec.name(), description(spec.name()), parametersSchema(objectMapper, spec)))
                .toList();
    }

    /** @return the toolset owning a declared key, or {@code null} when the key is unknown */
    public Toolset toolsetOf(String name) {
        ToolSpec spec = TOOLS.get(name);
        return spec == null ? null : spec.toolset();
    }

    /**
     * @param name declared tool key
     * @param loadedToolsets the toolsets the turn currently holds
     * @return whether the key is declared and its toolset is held
     */
    public boolean isLoaded(String name, Set<Toolset> loadedToolsets) {
        ToolSpec spec = TOOLS.get(name);
        return spec != null && loadedToolsets.contains(spec.toolset());
    }

    /**
     * The worst-case loaded set a turn can reach, used to size the one prompt budget a turn gets.
     *
     * <p>It is {@link #CORE} plus the {@link #MAX_ACTIVE_TOOLSETS_PER_TURN} weightiest loadable
     * toolsets and {@link #RESERVATION_HEADROOM_TOOLSETS} more. Weight is a pure-catalog proxy —
     * per tool, one for the declaration plus one per argument plus one per closed enum value —
     * because the catalog cannot serialize a prompt, which is why the headroom exists.
     * {@code AiAssistantPromptEnvelopeTest} measures the true envelope for every reachable
     * combination on both protocols and fails loudly, with the numbers printed, if this ever stops
     * dominating them.
     *
     * <p>It still grows with the largest declared families rather than with the catalog, which is
     * the point: reserving for every tool would fail the context floor as soon as the write
     * vocabulary grows, even though no turn would ever send those bytes.
     *
     * @return the toolsets the fixed envelope must be measured against
     */
    public Set<Toolset> reservationToolsets() {
        EnumSet<Toolset> reservation = EnumSet.copyOf(CORE);
        LOADABLE.stream()
                .sorted(Comparator.comparingInt(this::toolsetWeight).reversed()
                        .thenComparing(Comparator.comparingInt(Toolset::ordinal)))
                .limit(MAX_ACTIVE_TOOLSETS_PER_TURN + RESERVATION_HEADROOM_TOOLSETS)
                .forEach(reservation::add);
        return Collections.unmodifiableSet(reservation);
    }

    /**
     * The one model-visible statement of the enforced per-turn toolset cap.
     *
     * <p>Rendered from {@link #MAX_ACTIVE_TOOLSETS_PER_TURN} rather than written out, because the
     * {@code find_tools} description and the system-prompt directive are the only places a model
     * learns the limit: a hard-coded numeral beside a changed constant would tell every turn a
     * number the loader does not enforce, and no assertion over a literal can catch that drift.
     *
     * @return the sentence both the tool description and the prompt directive end with
     */
    public static String capSentence() {
        return "a request may hold at most " + MAX_ACTIVE_TOOLSETS_PER_TURN
                + " sets beyond the core set.";
    }

    /**
     * Whether a string is server-authored catalog vocabulary rather than tenant or model text.
     *
     * <p>Exists so the one tool result the server writes itself — {@code find_tools} — can be
     * replayed verbatim instead of through the tenant-data masking pass, while still failing
     * closed if that result ever gains a value the catalog did not author.
     *
     * @param value one string from a server-authored tool result
     * @return whether the catalog declares it as a tool name or a toolset key
     */
    public boolean isDeclaredVocabulary(String value) {
        if (value == null) {
            return false;
        }
        if (TOOLS.containsKey(value)) {
            return true;
        }
        for (Toolset toolset : Toolset.values()) {
            if (toolset.key().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** @return every loadable toolset paired with its server-authored summary, in declaration order */
    public List<Map.Entry<Toolset, String>> directory() {
        return LOADABLE.stream()
                .map(toolset -> Map.entry(toolset, toolset.summary()))
                .toList();
    }

    private int toolsetWeight(Toolset toolset) {
        return TOOLS.values().stream()
                .filter(spec -> spec.toolset() == toolset)
                .mapToInt(AiAssistantToolCatalog::declarationWeight)
                .sum();
    }

    private static int declarationWeight(ToolSpec spec) {
        int weight = 1 + spec.arguments().size();
        for (ArgumentSpec argument : spec.arguments()) {
            weight += argument.values().size();
        }
        return weight;
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
            case TEXT_LIST -> permitsTextList(argument, value);
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

    private static boolean permitsTextList(ArgumentSpec argument, JsonNode value) {
        if (!value.isArray()
                || value.size() < argument.minimum() || value.size() > argument.maximum()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isString() || item.asString().isBlank()
                    || item.asString().length() > MAX_TEXT_LIST_ITEM_CHARS) {
                return false;
            }
        }
        return true;
    }

    private static boolean permitsStringList(ArgumentSpec argument, JsonNode value) {
        if (!value.isArray() || value.size() < argument.minimum() || value.size() > argument.maximum()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isString()) {
                return false;
            }
            if (argument.values().isEmpty()) {
                if (!"handles".equals(argument.name())
                        || !HANDLE.matcher(item.asString()).matches()) {
                    return false;
                }
                continue;
            }
            if (!argument.values().contains(item.asString())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, ToolSpec> buildTools() {
        Map<String, ToolSpec> tools = new LinkedHashMap<>();
        add(tools, executable(Toolset.CORE, "search_records",
                string("query", true, 1, 200, Set.of()),
                stringList("kinds", false, 1, 3, Set.of("person", "company", "deal"))));
        add(tools, executable(Toolset.CORE, "get_record", handle()));
        add(tools, executable(Toolset.CORE, "get_records",
                stringList("handles", true, 1, 12, Set.of())));
        add(tools, executable(Toolset.CORE, "set_todos",
                textList("items", true, 1, 12),
                stringList("statuses", false, 1, 12,
                        Set.of("pending", "active", "done"))));
        add(tools, executable(Toolset.CORE, "list_activities",
                handle(), integer("limit", false, 1, 20)));
        add(tools, executable(Toolset.CORE, "list_tasks",
                handle(), integer("limit", false, 1, 20)));
        add(tools, executable(Toolset.CORE, "list_scope_activities",
                string("records", false, 4, 7, Set.of("person", "company", "deal")),
                stringList("warmth", false, 1, 4, Set.of("hot", "warm", "cool", "cold")),
                integer("days", false, 1, 365)));
        add(tools, executable(Toolset.CORE, FIND_TOOLS,
                string("toolset", true, 1, 32, loadableKeys())));
        add(tools, executable(Toolset.ANALYTICS, "aggregate_metric",
                string("metric", true, 1, 32, Set.of(
                        "deal_metrics", "deal_kpis", "activity_volume",
                        "task_summary", "warmth_summary")),
                string("currency", false, 1, 8, Set.of()),
                integer("days", false, 30, 365, Set.of("30", "90", "365")),
                string("scope", false, 0, 10, Set.of("", "me", "unassigned"))));
        add(tools, executable(Toolset.SCHEDULE, "find_schedule_conflicts",
                handle(),
                string("start", true, 1, 80, Set.of()),
                string("end", true, 1, 80, Set.of())));
        add(tools, reservedDealBrief());
        add(tools, auto(Toolset.WRITE_ACTIVITY, "create_activity",
                handle(),
                string("type", true, 1, 32, Set.of()),
                string("subject", true, 1, 255, Set.of()),
                string("notes", false, 0, 50_000, Set.of()),
                string("start", true, 1, 80, Set.of()),
                integer("duration_minutes", false, 1, 1_440)));
        add(tools, auto(Toolset.WRITE_ACTIVITY, "create_task",
                handle(),
                string("description", true, 1, 1_000, Set.of()),
                string("due_date", false, 0, 32, Set.of())));
        add(tools, auto(Toolset.WRITE_CONTENT, "create_note",
                handle(),
                string("content", true, 1, 50_000, Set.of()),
                string("title", false, 0, 255, Set.of()),
                string("visibility", false, 1, 9, Set.of("private", "workspace"))));
        add(tools, auto(Toolset.WRITE_CONTENT, "add_tag",
                handle(),
                string("tag", true, 1, 64, Set.of())));
        add(tools, confirm(Toolset.WRITE_PIPELINE, "change_deal_stage",
                handle(),
                string("stage", true, 1, 128, Set.of())));
        add(tools, confirm(Toolset.WRITE_PIPELINE, "assign_owner",
                handle(),
                string("owner", true, 1, 255, Set.of())));
        return Collections.unmodifiableMap(new LinkedHashMap<>(tools));
    }

    /**
     * Derives the closed {@code find_tools} enum from the loadable toolset keys.
     *
     * <p>A static method rather than a constant on purpose: {@code buildTools()} runs inside this
     * class's static initialiser, so a field declared after {@link #TOOLS} would still be null
     * here and {@code ArgumentSpec}'s {@code Set.copyOf} would throw out of {@code <clinit>} as an
     * {@code ExceptionInInitializerError} on first catalog use rather than where it was written.
     * A nested enum initialises on first use independently of the outer class's field order.
     *
     * @return the stable wire keys of every non-core toolset
     */
    private static Set<String> loadableKeys() {
        return java.util.Arrays.stream(Toolset.values())
                .filter(toolset -> toolset != Toolset.CORE)
                .map(Toolset::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String description(String name) {
        return switch (name) {
            case "search_records" -> "Search visible people, companies, and deals and return reusable handles.";
            case "get_record" -> "Load the visible details for one record handle.";
            case "get_records" -> "Load the visible details for up to twelve record handles "
                    + "in one step.";
            case "set_todos" -> "Publish or update the plan for this turn: items lists the steps "
                    + "in order, statuses gives each one pending, active, or done. Call it again "
                    + "with the whole updated list as you work.";
            case "list_activities" -> "List recent visible activities for one record handle.";
            case "list_tasks" -> "List visible tasks for one record handle.";
            case "list_scope_activities" -> "List recent activity across a bounded set of records "
                    + "in one call instead of asking record by record.";
            case FIND_TOOLS -> "Load one more named set of tools when the loaded sets cannot do "
                    + "the job. A set can be loaded once; " + capSentence();
            case "aggregate_metric" -> "Calculate a supported workspace relationship or pipeline metric.";
            case "find_schedule_conflicts" -> "Find visible scheduling conflicts for one record and time range.";
            case "create_activity" -> "Create an immediately executed, undoable activity for one record.";
            case "create_task" -> "Create an immediately executed, undoable task for one record.";
            case "create_note" -> "Create an immediately executed, undoable note for one record.";
            case "add_tag" -> "Add a tag immediately to one record.";
            case "change_deal_stage" -> "Propose a deal-stage change that requires human confirmation.";
            case "assign_owner" -> "Propose an owner assignment that requires human confirmation.";
            default -> throw new IllegalStateException("Assistant native tool description is missing");
        };
    }

    private static ObjectNode parametersSchema(ObjectMapper objectMapper, ToolSpec tool) {
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
                value.put("minLength", argument.minimum());
                value.put("maxLength", argument.maximum());
                addEnum(value, argument);
            }
            case INTEGER -> {
                value.put("type", "integer");
                value.put("minimum", argument.minimum());
                value.put("maximum", argument.maximum());
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
                value.put("minItems", argument.minimum());
                value.put("maxItems", argument.maximum());
                ObjectNode items = value.putObject("items");
                items.put("type", "string");
                addEnum(items, argument);
            }
            case TEXT_LIST -> {
                value.put("type", "array");
                value.put("minItems", argument.minimum());
                value.put("maxItems", argument.maximum());
                ObjectNode items = value.putObject("items");
                items.put("type", "string");
                items.put("minLength", 1);
                items.put("maxLength", MAX_TEXT_LIST_ITEM_CHARS);
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

    /**
     * Reserved until deal briefs can be read without a cache-miss model invocation, which would
     * otherwise nest provider egress inside one assistant step.
     */
    private static ToolSpec reservedDealBrief() {
        return reserved(
                Toolset.ANALYTICS,
                "get_deal_brief",
                "deal_brief_nested_generation_unavailable",
                handle());
    }

    private static void add(Map<String, ToolSpec> tools, ToolSpec spec) {
        tools.put(spec.name(), spec);
    }

    private static ToolSpec executable(
            Toolset toolset, String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, toolset, ToolTier.READ, true, null, List.of(arguments));
    }

    private static ToolSpec auto(Toolset toolset, String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, toolset, ToolTier.AUTO, true, null, List.of(arguments));
    }

    private static ToolSpec confirm(Toolset toolset, String name, ArgumentSpec... arguments) {
        return new ToolSpec(name, toolset, ToolTier.CONFIRM, true, null, List.of(arguments));
    }

    private static ToolSpec reserved(
            Toolset toolset, String name, String reason, ArgumentSpec... arguments) {
        return new ToolSpec(name, toolset, ToolTier.READ, false, reason, List.of(arguments));
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

    /**
     * Declares a bounded list of short free-text entries.
     *
     * <p>Unlike {@link #stringList}, the entries are the model's own words rather than a closed
     * vocabulary, so each is bounded to {@link #MAX_TEXT_LIST_ITEM_CHARS} — long enough for a plan
     * step, short enough that the list cannot become a channel.
     */
    private static ArgumentSpec textList(
            String name, boolean required, int minimum, int maximum) {
        return new ArgumentSpec(name, ArgumentKind.TEXT_LIST, required, minimum, maximum, Set.of());
    }
}
