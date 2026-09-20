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
            "afterToolCalls", "closing", "onRepair", "emit");
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
        return Map.copyOf(bySelector);
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
        return parse(root, file);
    }

    private static ScriptedAiScript parse(JsonNode root, Path file) {
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
            steps.add(parseStep(stepNode, file));
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

    private static ScriptedAiStep parseStep(JsonNode node, Path file) {
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
        try {
            return new ScriptedAiStep(
                    afterToolCalls.intValue(),
                    closing,
                    optionalBoolean(node, "onRepair", file),
                    parseEmission(node.path("emit"), file));
        } catch (IllegalArgumentException exception) {
            throw invalid(file, exception.getMessage());
        }
    }

    private static ScriptedAiStep.Emission parseEmission(JsonNode node, Path file) {
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
        return new ScriptedAiStep.Emission(
                kind,
                optionalText(node, "toolName"),
                optionalText(node, "arguments"),
                optionalText(node, "text"),
                failureKind,
                optionalText(node, "reasoning"),
                deltas);
    }

    private static void requireMaskingSafeSelector(String selector, Path file) {
        if (!selector.equals(MaskingEngine.maskFreeText(selector, new MaskingContext()))) {
            throw invalid(file, "selector does not survive masking unchanged");
        }
    }

    private static void requireDeclaredDegradation(ScriptedAiScript script, Path file) {
        if (script.expectsNativeDegradation()) {
            return;
        }
        for (ScriptedAiStep step : script.steps()) {
            if (step.afterToolCalls() == 0
                    && step.emit().kind() == ScriptedAiStep.Kind.FAILURE
                    && step.emit().failureKind() == ScriptedAiStep.FailureKind.REJECTED) {
                throw invalid(file, "a rejected failure on the first step degrades the turn to the "
                        + "JSON protocol instead of failing it, so the script must declare "
                        + "expectsNativeDegradation");
            }
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
