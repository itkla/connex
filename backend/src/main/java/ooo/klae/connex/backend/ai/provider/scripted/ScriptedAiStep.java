package ooo.klae.connex.backend.ai.provider.scripted;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One scripted step: the predicate that selects it and the emission it produces.
 *
 * <p>The predicate is matched against a {@link ScriptedAiTurnCursor} derived purely from the
 * request, so a scripted turn needs no mutable per-turn state and no control channel.
 *
 * @param afterToolCalls completed tool calls this step answers after
 * @param closing whether this step must be the loop's closing step, or {@code null} for either
 * @param onRepair whether this step answers a schema-repair request rather than a first attempt
 * @param emit what the provider returns, or the failure it raises
 */
public record ScriptedAiStep(
        int afterToolCalls,
        Boolean closing,
        boolean onRepair,
        Emission emit) {

    public ScriptedAiStep {
        if (afterToolCalls < 0) {
            throw new IllegalArgumentException("Scripted AI step afterToolCalls must not be negative");
        }
        Objects.requireNonNull(emit, "emit");
    }

    /**
     * Whether this step answers the supplied request cursor.
     * @param cursor request-derived position in the turn
     * @return whether the predicate matches
     */
    public boolean matches(ScriptedAiTurnCursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        return afterToolCalls == cursor.completedToolCalls()
                && onRepair == cursor.repairAttempt()
                && (closing == null || closing == cursor.closing());
    }

    /** What the provider does when a step is selected. */
    public enum Kind {
        /** Returns one function call. */
        TOOL_CALL,
        /** Returns the full terminal step JSON. */
        FINAL,
        /** Returns deliberately unparseable text. */
        MALFORMED,
        /** Raises a typed provider failure after the dispatch is recorded. */
        FAILURE
    }

    /** Typed provider failure a scripted step may raise. */
    public enum FailureKind {
        /** Generic adapter or transport failure. */
        TRANSPORT,
        /** Sanitized provider HTTP rejection with a client-error status. */
        REJECTED,
        /** Provider stream exceeded its inactivity interval. */
        IDLE_TIMEOUT,
        /** Provider transport exhausted the caller-owned deadline. */
        DEADLINE
    }

    /**
     * The scripted emission for one step.
     *
     * @param kind what the provider returns or raises
     * @param toolName function name for {@link Kind#TOOL_CALL}
     * @param arguments function arguments JSON text for {@link Kind#TOOL_CALL}
     * @param text returned model text for {@link Kind#FINAL} and {@link Kind#MALFORMED}
     * @param failureKind typed failure for {@link Kind#FAILURE}
     * @param reasoning display-only reasoning returned beside the content, or an empty string
     * @param deltas ordered streamed fragments whose concatenation equals {@link #text}
     */
    public record Emission(
            Kind kind,
            String toolName,
            String arguments,
            String text,
            FailureKind failureKind,
            String reasoning,
            List<String> deltas) {

        private static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

        public Emission {
            Objects.requireNonNull(kind, "kind");
            reasoning = reasoning == null ? "" : reasoning;
            deltas = List.copyOf(Objects.requireNonNull(deltas, "deltas"));
            switch (kind) {
                case TOOL_CALL -> {
                    if (toolName == null || !TOOL_NAME.matcher(toolName).matches()) {
                        throw new IllegalArgumentException("Scripted AI tool name is invalid");
                    }
                    if (arguments == null || arguments.isBlank()) {
                        throw new IllegalArgumentException(
                                "Scripted AI tool arguments are required");
                    }
                }
                case FINAL, MALFORMED -> {
                    if (text == null || text.isBlank()) {
                        throw new IllegalArgumentException("Scripted AI step text is required");
                    }
                }
                case FAILURE -> {
                    if (failureKind == null) {
                        throw new IllegalArgumentException("Scripted AI failure kind is required");
                    }
                }
            }
            if (!deltas.isEmpty() && !String.join("", deltas).equals(text == null ? "" : text)) {
                throw new IllegalArgumentException(
                        "Scripted AI step deltas must concatenate to its text");
            }
        }
    }
}
