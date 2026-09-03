package ooo.klae.connex.backend.delivery;

import java.util.regex.Pattern;

/**
 * The connector-reported outcome of an audience push: how many members the external service accepted,
 * how many it rejected, the certainty class of the provider interaction, and a bounded human-readable
 * detail. Connectors map expected transport and vendor errors into this result; the export choke point
 * treats an unexpected connector exception as an ambiguous outcome requiring reconciliation.
 * @param pushedCount members the connector accepted
 * @param failedCount members the connector rejected
 * @param detail a bounded human-readable outcome detail, or null
 * @param outcome whether acceptance was confirmed, definitely impossible, or remains ambiguous
 * @param failureReason a bounded recipient-free diagnostic code for a definitive failure, otherwise null
 */
public record AudiencePushResult(
        int pushedCount, int failedCount, String detail, Outcome outcome, String failureReason) {

    private static final Pattern FAILURE_REASON = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** The three possible provider-side effect classifications for one audience request. */
    public enum Outcome {
        CONFIRMED,
        DEFINITE_NO_SIDE_EFFECT,
        AMBIGUOUS
    }

    /**
     * Backward-compatible constructor for connectors that return a confirmed outcome.
     * @param pushedCount members the connector accepted
     * @param failedCount members the connector rejected
     * @param detail a bounded human-readable outcome detail, or null
     */
    public AudiencePushResult(int pushedCount, int failedCount, String detail) {
        this(pushedCount, failedCount, detail, Outcome.CONFIRMED, null);
    }

    /**
     * Backward-compatible constructor for outcomes without a fixed diagnostic code.
     * @param pushedCount members the connector accepted
     * @param failedCount members the connector rejected
     * @param detail a bounded human-readable outcome detail, or null
     * @param outcome whether acceptance was confirmed, definitely impossible, or remains ambiguous
     */
    public AudiencePushResult(int pushedCount, int failedCount, String detail, Outcome outcome) {
        this(pushedCount, failedCount, detail, outcome,
                outcome == Outcome.DEFINITE_NO_SIDE_EFFECT ? "connector_definitive_failure" : null);
    }

    public AudiencePushResult {
        if (pushedCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("Audience push counts must be non-negative");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("Audience push outcome is required");
        }
        if (failureReason != null && !FAILURE_REASON.matcher(failureReason).matches()) {
            throw new IllegalArgumentException("Audience push failure reason must be a bounded code");
        }
        if ((outcome == Outcome.DEFINITE_NO_SIDE_EFFECT) != (failureReason != null)) {
            throw new IllegalArgumentException("Audience push failure reason must match a definitive outcome");
        }
    }

    /**
     * A result recording that the provider could not have applied the request.
     * @param failedCount the number of members that could not be pushed
     * @param detail a bounded human-readable failure detail
     * @return the definite no-side-effect result
     */
    public static AudiencePushResult definiteNoSideEffect(int failedCount, String detail) {
        return definiteNoSideEffect(failedCount, detail, "connector_definitive_failure");
    }

    /**
     * A result recording that the provider could not have applied the request.
     * @param failedCount the number of members that could not be pushed
     * @param detail a bounded human-readable failure detail
     * @param failureReason a bounded recipient-free diagnostic code
     * @return the definite no-side-effect result
     */
    public static AudiencePushResult definiteNoSideEffect(
            int failedCount, String detail, String failureReason) {
        return new AudiencePushResult(
                0, Math.max(failedCount, 0), detail, Outcome.DEFINITE_NO_SIDE_EFFECT, failureReason);
    }

    /**
     * An attempted provider request whose acceptance result could not be established.
     * @param memberCount members in the request
     * @param detail a bounded human-readable failure detail
     * @return an outcome that requires operator reconciliation
     */
    public static AudiencePushResult ambiguous(int memberCount, String detail) {
        return new AudiencePushResult(0, Math.max(memberCount, 0), detail, Outcome.AMBIGUOUS, null);
    }
}
