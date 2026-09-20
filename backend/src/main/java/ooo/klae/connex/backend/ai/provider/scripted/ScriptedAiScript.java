package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One immutable scripted trajectory: how a request selects it, and what it answers at each step.
 *
 * @param id fixture identifier, unique across the fixture directory
 * @param selector literal token a request must carry for this script to answer it
 * @param capabilityClass declared capability shape the configured model id must resolve to
 * @param expectsNativeDegradation whether this script deliberately degrades a native turn to JSON
 * @param steps ordered steps, matched first-match-wins against the request cursor
 */
public record ScriptedAiScript(
        String id,
        String selector,
        ScriptedAiCapabilityClass capabilityClass,
        boolean expectsNativeDegradation,
        List<ScriptedAiStep> steps) {

    /** Fixture identifier shape. */
    public static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{2,48}$");

    /**
     * Selector shape, deliberately free of digits.
     *
     * <p>Every selector travels through {@code MaskingEngine} before it reaches the provider, and
     * a selector the masker rewrote would silently stop matching — every trajectory would then
     * refuse with {@code provider_error} for a reason no assertion names. None of the masker's
     * detectors (URL, phone-like runs, long digit runs, ISO temporals, {@code {{A<n>}}}
     * placeholders and standalone {@code r<n>} handles) can match a lowercase token with no
     * digits, which is what this pattern guarantees.
     */
    public static final Pattern SELECTOR = Pattern.compile("^connex_script_[a-z_]{3,48}$");

    public ScriptedAiScript {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Scripted AI script id is invalid");
        }
        if (selector == null || !SELECTOR.matcher(selector).matches()) {
            throw new IllegalArgumentException("Scripted AI script selector is invalid");
        }
        Objects.requireNonNull(capabilityClass, "capabilityClass");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Scripted AI script requires at least one step");
        }
        int previous = -1;
        for (ScriptedAiStep step : steps) {
            if (step.afterToolCalls() < previous) {
                throw new IllegalArgumentException(
                        "Scripted AI script steps must not decrease afterToolCalls");
            }
            previous = step.afterToolCalls();
        }
        for (int index = 0; index < steps.size(); index++) {
            for (int other = index + 1; other < steps.size(); other++) {
                if (overlaps(steps.get(index), steps.get(other))) {
                    throw new IllegalArgumentException(
                            "Scripted AI script steps must not share a predicate");
                }
            }
        }
    }

    /**
     * Resolves the step that answers one request cursor.
     * @param cursor request-derived position in the turn
     * @return the first matching step, or empty when the script does not answer this request
     */
    public Optional<ScriptedAiStep> resolve(ScriptedAiTurnCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        return steps.stream().filter(step -> step.matches(cursor)).findFirst();
    }

    /**
     * Whether two steps could both answer one request.
     *
     * <p>The protocol is part of the key, not a detail: a native step and a JSON step at the same
     * cursor position answer different requests, which is exactly what a fixture rehearsing the
     * native-to-JSON degradation needs to declare.
     *
     * @param first one declared step
     * @param second another declared step
     * @return whether their predicates can both match one cursor
     */
    private static boolean overlaps(ScriptedAiStep first, ScriptedAiStep second) {
        return first.afterToolCalls() == second.afterToolCalls()
                && first.onRepair() == second.onRepair()
                && first.protocol().overlaps(second.protocol())
                && (first.closing() == null
                    || second.closing() == null
                    || first.closing().equals(second.closing()));
    }
}
