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
 * @param protocol which wire protocol this step answers under
 * @param emit what the provider returns, or the failure it raises
 */
public record ScriptedAiStep(
        int afterToolCalls,
        Boolean closing,
        boolean onRepair,
        Protocol protocol,
        Emission emit) {

    public ScriptedAiStep {
        if (afterToolCalls < 0) {
            throw new IllegalArgumentException("Scripted AI step afterToolCalls must not be negative");
        }
        protocol = protocol == null ? Protocol.ANY : protocol;
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
                && protocol.admits(cursor.nativeProtocol())
                && (closing == null || closing == cursor.closing());
    }

    /**
     * The wire protocol a step answers under, as a closed set.
     *
     * <p>The protocol is part of the predicate because one turn can visit both. A client-error
     * rejection on a native first attempt does not fail the turn: the loop clears its native state
     * and retries the same cursor position through the JSON protocol. Without this dimension the
     * retry reselects the rejecting step — and the uniqueness rule that forbids two steps sharing
     * {@code (afterToolCalls, onRepair, closing)} then makes the successful follow-up
     * inexpressible, so a degradation fixture could only ever end in a second rejection.
     */
    public enum Protocol {
        /** Answers only a native-tool request. */
        NATIVE,
        /** Answers only a JSON-protocol request. */
        JSON,
        /** Answers either protocol. */
        ANY;

        /**
         * Whether this step may answer a request on the supplied protocol.
         * @param nativeProtocol whether the request carries a native-tool request
         * @return whether the protocol admits it
         */
        public boolean admits(boolean nativeProtocol) {
            return this == ANY || (this == NATIVE) == nativeProtocol;
        }

        /**
         * Whether two declared protocols can both answer one request.
         * @param other the other step's declared protocol
         * @return whether the two overlap
         */
        public boolean overlaps(Protocol other) {
            return this == ANY || other == ANY || this == other;
        }
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
