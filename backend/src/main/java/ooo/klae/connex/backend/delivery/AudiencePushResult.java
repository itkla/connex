package ooo.klae.connex.backend.delivery;

/**
 * The connector-reported outcome of an audience push: how many members the external service accepted,
 * how many it rejected, and a bounded human-readable detail. A connector never throws for a transport
 * or vendor error; it maps the failure into this result so the export choke point can record it.
 * @param pushedCount members the connector accepted
 * @param failedCount members the connector rejected
 * @param detail a bounded human-readable outcome detail, or null
 */
public record AudiencePushResult(int pushedCount, int failedCount, String detail) {

    /**
     * A result recording that every member failed, for a transport or vendor error before any member
     * was accepted.
     * @param failedCount the number of members that could not be pushed
     * @param detail a bounded human-readable failure detail
     * @return the all-failed result
     */
    public static AudiencePushResult failed(int failedCount, String detail) {
        return new AudiencePushResult(0, Math.max(failedCount, 0), detail);
    }
}
