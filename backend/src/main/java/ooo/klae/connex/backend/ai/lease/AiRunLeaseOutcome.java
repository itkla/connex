package ooo.klae.connex.backend.ai.lease;

/**
 * The authoritative result of one lease renewal.
 *
 * <p>There is deliberately no {@code UNKNOWN} constant: a renewal that fails to reach the database
 * throws, so a transient database failure is retried by the heartbeat rather than mistaken for
 * ownership loss and used to abort a healthy run. Only a renewal that ran and matched zero rows is
 * {@link #LOST}.
 */
public enum AiRunLeaseOutcome {
    /** The renewal matched the holder's owner and epoch and extended the deadline. */
    HELD,
    /** The renewal ran and matched no row: another instance has taken the lease over. */
    LOST
}
