package ooo.klae.connex.backend.ai.lease;

import java.util.Objects;

/**
 * The fencing token one instance carries while it owns a run.
 *
 * <p>The invariant that makes this a fence rather than a hint: {@code epoch} is strictly increasing
 * per key for as long as the row exists. That holds only because releasing a lease tombstones the
 * row — owner cleared, {@code released_at} set — instead of deleting it. If release deleted the
 * row, the next claim would insert at epoch 1 again and a revived stale owner from an earlier claim
 * would match the new claim's row and believe it still held the lease.
 *
 * @param key the lease's primary key
 * @param owner the holding instance's owner id
 * @param epoch the fencing epoch every owner-side statement predicates on
 */
public record AiRunLease(AiRunLeaseKey key, String owner, long epoch) {

    /** Validates that a token names a key, an owner, and a persisted epoch. */
    public AiRunLease {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("AI run lease owner must not be blank");
        }
        if (epoch <= 0) {
            throw new IllegalArgumentException("AI run lease epoch must be positive");
        }
    }
}
