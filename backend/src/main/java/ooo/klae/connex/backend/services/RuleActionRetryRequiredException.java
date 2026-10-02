package ooo.klae.connex.backend.services;

/**
 * A rule action failed for a transient reason inside a durable delivery, so the whole delivery must
 * roll back and be retried rather than finishing {@code "partial"} (#1928).
 *
 * <p>A lock wait, a deadlock, or a savepoint the database has already discarded is not the action's
 * fault, and a deadlock takes the delivery's transaction with it, so nothing after it may run in that
 * delivery.
 */
public class RuleActionRetryRequiredException extends RuntimeException {
    public RuleActionRetryRequiredException(Throwable cause) {
        super("A rule action failed transiently; the delivery must be retried", cause);
    }
}
