package ooo.klae.connex.backend.delivery;

/**
 * The connector-reported outcome of an audience push: how many members the external service accepted,
 * how many it rejected, the certainty class of the provider interaction, and a bounded human-readable
 * detail. Connectors map expected transport and vendor errors into this result; the export choke point
 * treats an unexpected connector exception as an ambiguous outcome requiring reconciliation.
 * @param pushedCount members the connector accepted
 * @param failedCount members the connector rejected
 * @param detail a bounded human-readable outcome detail, or null
 * @param outcome whether acceptance was confirmed, definitely impossible, or remains ambiguous
 */
public record AudiencePushResult(
        int pushedCount, int failedCount, String detail, Outcome outcome) {

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
        this(pushedCount, failedCount, detail, Outcome.CONFIRMED);
    }

    public AudiencePushResult {
        if (pushedCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("Audience push counts must be non-negative");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("Audience push outcome is required");
        }
    }

    /**
     * A result recording that the provider could not have applied the request.
     * @param failedCount the number of members that could not be pushed
     * @param detail a bounded human-readable failure detail
     * @return the definite no-side-effect result
     */
    public static AudiencePushResult definiteNoSideEffect(int failedCount, String detail) {
        return new AudiencePushResult(
                0, Math.max(failedCount, 0), detail, Outcome.DEFINITE_NO_SIDE_EFFECT);
    }

    /**
     * An attempted provider request whose acceptance result could not be established.
     * @param memberCount members in the request
     * @param detail a bounded human-readable failure detail
     * @return an outcome that requires operator reconciliation
     */
    public static AudiencePushResult ambiguous(int memberCount, String detail) {
        return new AudiencePushResult(0, Math.max(memberCount, 0), detail, Outcome.AMBIGUOUS);
    }
}
