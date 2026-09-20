package ooo.klae.connex.backend.ai.provider.scripted;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads and validates every script under the configured fixture directory at startup.
 *
 * <p>Nothing is read from the classpath, and {@code backend/src/main/resources/ai/scripted}
 * deliberately does not exist: the shipped artifact contains zero scripts, so even an instance
 * that defeated the profile, the flag and the four startup refusals would answer nothing. The
 * directory must be set, exist, be a readable directory and contain at least one script, and the
 * application context fails to start otherwise.
 *
 * <p>Every validation here is fail-closed and every one of them exists because the failure it
 * prevents would otherwise be silent: a masked-away selector, a fixture that degrades a native
 * turn to JSON without saying so, streamed fragments that do not reconstruct the buffered text, a
 * credential shape pasted into a fixture.
 */
public class ScriptedAiScriptLoader {

    /** Refusal raised for an unset, missing, unreadable or script-free fixture directory. */
    public static final String FIXTURE_DIR_REFUSAL =
            ScriptedAiProviderProfile.FIXTURE_DIR_PROPERTY
                    + " must name a readable directory containing at least one script";

    /** Maximum scripts one fixture directory may declare. */
    public static final int MAX_SCRIPTS = 64;

    /** Maximum size of one script file in bytes. */
    public static final int MAX_SCRIPT_BYTES = 64 * 1024;

    private static final String SUFFIX = ".json";

    /**
     * Shapes a credential may take, refused wherever they appear in a script.
     *
     * <p>A fixture directory is test material that travels through pull requests and CI logs. The
     * repository's secret scanner runs over the whole pull-request range, so this rule exists to
     * catch the class before a commit does rather than after.
     */
    private static final Pattern SECRET_SHAPE = Pattern.compile(
            "(?i)(sk-|api[_-]?key|BEGIN [A-Z ]*PRIVATE KEY|[A-Za-z0-9+/]{40,}={0,2})");

    private static final Set<String> SCRIPT_FIELDS = Set.of(
            "id", "selector", "capabilityClass", "expectsNativeDegradation", "steps");
    private static final Set<String> STEP_FIELDS = Set.of(
            "afterToolCalls", "closing", "onRepair", "protocol", "emit");
    private static final Set<String> EMISSION_FIELDS = Set.of(
            "kind", "toolName", "arguments", "text", "failureKind", "reasoning", "deltas");

    private final Map<String, ScriptedAiScript> bySelector;

    /**
     * Loads every script in the configured directory.
     * @param fixtureDir configured fixture directory
     * @param objectMapper shared JSON mapper
     */
    public ScriptedAiScriptLoader(String fixtureDir, ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        this.bySelector = load(fixtureDir, objectMapper);
    }

    /** @return declared selectors, in load order */
    public Set<String> selectors() {
        return bySelector.keySet();
    }

    /** @return loaded scripts, in load order */
    public List<ScriptedAiScript> scripts() {
        return List.copyOf(bySelector.values());
    }

    /**
     * Resolves the script a selector names.
     * @param selector declared script selector
     * @return the script, or {@code null} when no script declares the selector
     */
    public ScriptedAiScript bySelector(String selector) {
        return bySelector.get(selector);
    }

    private static Map<String, ScriptedAiScript> load(String fixtureDir, ObjectMapper objectMapper) {
        if (fixtureDir == null || fixtureDir.isBlank()) {
            throw new IllegalStateException(FIXTURE_DIR_REFUSAL);
        }
        Path directory = Path.of(fixtureDir.trim());
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(directory)) {
            throw new IllegalStateException(FIXTURE_DIR_REFUSAL);
        }
        List<Path> files = scriptFiles(directory);
        if (files.isEmpty()) {
            throw new IllegalStateException(FIXTURE_DIR_REFUSAL);
        }
        if (files.size() > MAX_SCRIPTS) {
            throw new IllegalStateException(
                    "Scripted AI fixture directory declares more than " + MAX_SCRIPTS + " scripts");
        }
        Map<String, ScriptedAiScript> bySelector = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            ScriptedAiScript script = read(file, objectMapper);
            if (!ids.add(script.id())) {
                throw new IllegalStateException(
                        "Scripted AI script ids must be unique: " + script.id());
            }
            if (bySelector.putIfAbsent(script.selector(), script) != null) {
                throw new IllegalStateException(
                        "Scripted AI script selectors must be unique: " + script.selector());
            }
        }
        requireDisjointSelectors(bySelector.keySet());
        return Map.copyOf(bySelector);
    }

    /**
     * Refuses selectors that contain one another.
     *
     * <p>A selector is matched by substring search over the serialized prompt, so a request
     * carrying the longer of two nested selectors carries the shorter one as well and matches both.
     * The cursor then refuses the turn for having matched more than one script — a run-time failure
     * for a fixture fault, and exactly the class of silent selector error this loader exists to
     * catch at load time. Uniqueness by equality does not cover it.
     *
     * @param selectors every loaded selector
     */
    private static void requireDisjointSelectors(Set<String> selectors) {
        List<String> ordered = List.copyOf(selectors);
        for (int index = 0; index < ordered.size(); index++) {
            for (int other = index + 1; other < ordered.size(); other++) {
                String first = ordered.get(index);
                String second = ordered.get(other);
                if (first.contains(second) || second.contains(first)) {
                    throw new IllegalStateException(
                            "Scripted AI script selectors must not contain one another: "
                                    + first + ", " + second);
                }
            }
        }
    }

    private static List<Path> scriptFiles(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(FIXTURE_DIR_REFUSAL, exception);
        }
    }

    private static ScriptedAiScript read(Path file, ObjectMapper objectMapper) {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Scripted AI script could not be read: " + file.getFileName(), exception);
        }
        if (content.length > MAX_SCRIPT_BYTES) {
            throw new IllegalStateException(
                    "Scripted AI script exceeds " + MAX_SCRIPT_BYTES + " bytes: "
                            + file.getFileName());
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (SECRET_SHAPE.matcher(text).find()) {
            throw new IllegalStateException(
                    "Scripted AI script carries a credential-shaped value: " + file.getFileName());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Scripted AI script is not valid JSON: " + file.getFileName(), exception);
        }
        return parse(root, file, objectMapper);
    }

    private static ScriptedAiScript parse(JsonNode root, Path file, ObjectMapper objectMapper) {
        requireObject(root, file);
        requireKnownFields(root, SCRIPT_FIELDS, file);
        String id = requiredText(root, "id", file);
        String selector = requiredText(root, "selector", file);
        ScriptedAiCapabilityClass capabilityClass = ScriptedAiCapabilityClass.forModelId(
                requiredText(root, "capabilityClass", file));
        boolean expectsNativeDegradation = optionalBoolean(
                root, "expectsNativeDegradation", file);
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw invalid(file, "steps must be a non-empty array");
        }
        List<ScriptedAiStep> steps = new ArrayList<>(stepsNode.size());
        for (JsonNode stepNode : stepsNode) {
            steps.add(parseStep(stepNode, file, objectMapper));
        }
        ScriptedAiScript script;
        try {
            script = new ScriptedAiScript(
                    id, selector, capabilityClass, expectsNativeDegradation, steps);
        } catch (IllegalArgumentException exception) {
            throw invalid(file, exception.getMessage());
        }
        requireMaskingSafeSelector(script.selector(), file);
        requireDeclaredDegradation(script, file);
        return script;
    }

    private static ScriptedAiStep parseStep(JsonNode node, Path file, ObjectMapper objectMapper) {
        requireObject(node, file);
        requireKnownFields(node, STEP_FIELDS, file);
        JsonNode afterToolCalls = node.path("afterToolCalls");
        if (!afterToolCalls.isIntegralNumber() || !afterToolCalls.canConvertToInt()) {
            throw invalid(file, "afterToolCalls must be an integer");
        }
        Boolean closing = null;
        JsonNode closingNode = node.get("closing");
        if (closingNode != null && !closingNode.isNull()) {
            if (!closingNode.isBoolean()) {
                throw invalid(file, "closing must be a boolean");
            }
            closing = closingNode.booleanValue();
        }
        JsonNode protocolNode = node.get("protocol");
        ScriptedAiStep.Protocol protocol = protocolNode == null || protocolNode.isNull()
                ? ScriptedAiStep.Protocol.ANY
                : enumValue(
                        ScriptedAiStep.Protocol.class,
                        requiredText(node, "protocol", file),
                        file,
                        "protocol");
        try {
            return new ScriptedAiStep(
                    afterToolCalls.intValue(),
                    closing,
                    optionalBoolean(node, "onRepair", file),
                    protocol,
                    parseEmission(node.path("emit"), file, objectMapper));
        } catch (IllegalArgumentException exception) {
            throw invalid(file, exception.getMessage());
        }
    }

    private static ScriptedAiStep.Emission parseEmission(
            JsonNode node, Path file, ObjectMapper objectMapper) {
        requireObject(node, file);
        requireKnownFields(node, EMISSION_FIELDS, file);
        ScriptedAiStep.Kind kind = enumValue(
                ScriptedAiStep.Kind.class, requiredText(node, "kind", file), file, "kind");
        JsonNode failureKindNode = node.get("failureKind");
        ScriptedAiStep.FailureKind failureKind = failureKindNode == null
                || failureKindNode.isNull()
                ? null
                : enumValue(
                        ScriptedAiStep.FailureKind.class,
                        requiredText(node, "failureKind", file),
                        file,
                        "failureKind");
        List<String> deltas = new ArrayList<>();
        JsonNode deltasNode = node.get("deltas");
        if (deltasNode != null && !deltasNode.isNull()) {
            if (!deltasNode.isArray()) {
                throw invalid(file, "deltas must be an array");
            }
            for (JsonNode delta : deltasNode) {
                if (!delta.isString()) {
                    throw invalid(file, "deltas must contain only strings");
                }
                deltas.add(delta.asString());
            }
        }
        String arguments = optionalText(node, "arguments");
        if (kind == ScriptedAiStep.Kind.TOOL_CALL) {
            requireJsonObjectArguments(arguments, file, objectMapper);
        }
        return new ScriptedAiStep.Emission(
                kind,
                optionalText(node, "toolName"),
                arguments,
                optionalText(node, "text"),
                failureKind,
                optionalText(node, "reasoning"),
                deltas);
    }

    /**
     * Refuses tool-call arguments that are not a JSON object.
     *
     * <p>The provider never re-encodes this value: on the native path it becomes the function
     * call's arguments verbatim, and on the JSON path it is spliced into the step envelope as raw
     * JSON. A fixture typo therefore reaches the loop as malformed model output or as a refused
     * tool call — a runtime failure wearing the costume of a product defect — unless it is refused
     * here, where the loader refuses rather than defaults everywhere else.
     *
     * @param arguments the declared arguments text
     * @param file the fixture the arguments came from
     * @param objectMapper shared JSON mapper
     */
    private static void requireJsonObjectArguments(
            String arguments, Path file, ObjectMapper objectMapper) {
        if (arguments == null || arguments.isBlank()) {
            throw invalid(file, "arguments are required");
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(arguments);
        } catch (JacksonException exception) {
            throw invalid(file, "arguments must be a JSON object");
        }
        if (parsed == null || !parsed.isObject()) {
            throw invalid(file, "arguments must be a JSON object");
        }
    }

    /**
     * Refuses a selector that {@code MaskingEngine} would rewrite on its way to the provider.
     *
     * <p>Unreachable today by construction: {@link ScriptedAiScript#SELECTOR} admits only lowercase
     * letters and underscores, and every masker detector needs a digit, a scheme or a dot. It is
     * kept, and kept package-private so its own test can reach it, because a future detector could
     * make it reachable, and the failure it prevents is completely silent — a rewritten selector
     * matches nothing, so every trajectory refuses with {@code provider_error} for a reason no
     * assertion names.
     *
     * @param selector the declared selector
     * @param file the fixture the selector came from
     */
    static void requireMaskingSafeSelector(String selector, Path file) {
        if (!selector.equals(MaskingEngine.maskFreeText(selector, new MaskingContext()))) {
            throw invalid(file, "selector does not survive masking unchanged");
        }
    }

    /**
     * Refuses a script whose first native step rejects without a declared, answerable degradation.
     *
     * <p>A client-error rejection on the first native attempt does not fail the turn: the loop
     * clears its native state and retries the same cursor position through the JSON protocol. Two
     * silent fixtures follow from that. One declares the rejection without meaning to and produces
     * a green golden that documents native behaviour it never exercised. The other declares the
     * degradation but no JSON step for the retried position, so the retry reselects the rejecting
     * step and the trajectory terminates in a second rejection having rehearsed nothing.
     *
     * <p>A rejecting <em>repair</em> step is outside this rule. The loop degrades only on its first
     * native attempt; a repair attempt is by definition a later one, so a rejection there fails the
     * turn, and demanding a degradation declaration for it would make the fixture state something
     * false about its own trajectory.
     *
     * @param script the parsed script
     * @param file the fixture it came from
     */
    private static void requireDeclaredDegradation(ScriptedAiScript script, Path file) {
        boolean rejectsFirstNativeStep = script.steps().stream().anyMatch(step ->
                step.afterToolCalls() == 0
                        && !step.onRepair()
                        && step.protocol().admits(true)
                        && step.emit().kind() == ScriptedAiStep.Kind.FAILURE
                        && step.emit().failureKind() == ScriptedAiStep.FailureKind.REJECTED);
        if (!rejectsFirstNativeStep) {
            return;
        }
        if (!script.expectsNativeDegradation()) {
            throw invalid(file, "a rejected failure on the first step degrades the turn to the "
                    + "JSON protocol instead of failing it, so the script must declare "
                    + "expectsNativeDegradation");
        }
        boolean answersTheRetry = script.steps().stream().anyMatch(step ->
                step.afterToolCalls() == 0
                        && !step.onRepair()
                        && step.protocol().admits(false)
                        && (step.closing() == null || !step.closing())
                        && !(step.emit().kind() == ScriptedAiStep.Kind.FAILURE
                                && step.emit().failureKind()
                                        == ScriptedAiStep.FailureKind.REJECTED));
        if (!answersTheRetry) {
            throw invalid(file, "a script that expects the native protocol to degrade must also "
                    + "declare a JSON-protocol step for the retried first position, or the turn "
                    + "can only end in a second rejection");
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, Path file, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(file, field + " is not a declared value: " + value);
        }
    }

    private static void requireObject(JsonNode node, Path file) {
        if (node == null || !node.isObject()) {
            throw invalid(file, "expected a JSON object");
        }
    }

    private static void requireKnownFields(JsonNode node, Set<String> known, Path file) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!known.contains(entry.getKey())) {
                throw invalid(file, "unknown field " + entry.getKey());
            }
        }
    }

    private static String requiredText(JsonNode node, String field, Path file) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw invalid(file, field + " is required");
        }
        return value.asString();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static boolean optionalBoolean(JsonNode node, String field, Path file) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid(file, field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static IllegalStateException invalid(Path file, String detail) {
        return new IllegalStateException(
                "Scripted AI script " + file.getFileName() + " is invalid: " + detail);
    }
}
