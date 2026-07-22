package ooo.klae.connex.backend.ai;

import java.util.Objects;

/**
 * Result of a structured AI invocation. A {@link Parsed} carries the demasked, type-bound content;
 * a {@link Malformed} carries only a stable reason and never the raw provider text, so a caller
 * cannot accidentally surface unparsed model output to a user.
 * @param <T> feature content type
 */
public sealed interface AiStructuredOutcome<T> permits AiStructuredOutcome.Parsed, AiStructuredOutcome.Malformed {

    /** Provider output was cut off before a complete JSON object was emitted. */
    String REASON_TRUNCATED = "truncated";
    /** Provider output did not contain a usable JSON object for the requested shape. */
    String REASON_MALFORMED = "malformed_output";

    /**
     * Successful structured completion.
     * @param value demasked, type-bound content
     * @param demaskWarnings number of unknown placeholder references encountered while demasking
     * @param inputTokens provider-reported input token count
     * @param outputTokens provider-reported output token count
     * @param stopReason provider stop reason
     * @param <T> feature content type
     */
    record Parsed<T>(T value, int demaskWarnings, int inputTokens, int outputTokens, String stopReason)
            implements AiStructuredOutcome<T> {

        public Parsed {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Parsed[value=<redacted>"
                    + ", demaskWarnings=" + demaskWarnings
                    + ", inputTokens=" + inputTokens
                    + ", outputTokens=" + outputTokens
                    + ", stopReason=" + stopReason + "]";
        }
    }

    /**
     * Structured completion that could not be parsed into the requested shape.
     * @param reason stable failure reason drawn from {@link #REASON_TRUNCATED} or {@link #REASON_MALFORMED}
     * @param inputTokens provider-reported input token count
     * @param outputTokens provider-reported output token count
     * @param stopReason provider stop reason
     * @param <T> feature content type
     */
    record Malformed<T>(String reason, int inputTokens, int outputTokens, String stopReason)
            implements AiStructuredOutcome<T> {

        public Malformed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
