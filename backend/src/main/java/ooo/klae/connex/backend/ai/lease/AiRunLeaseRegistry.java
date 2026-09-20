package ooo.klae.connex.backend.ai.lease;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * JVM-local store of the fencing tokens this instance currently offers.
 *
 * <p>A claim and its durable terminal write happen in different call stacks, so the token has to
 * live somewhere between them without widening the queued-turn value object or the terminal write's
 * signature. This registry is that place, and it is JVM-local by design: the durable fence is the
 * {@code (owner, epoch)} pair in MySQL, and the registry only decides which token this instance
 * offers. A missing entry therefore makes a release a correct no-op — it means a takeover has
 * already re-fenced the row.
 *
 * <p>Every mutation is driven by {@link AiRunLeaseService} from a transaction-completion callback,
 * so this map cannot disagree with the committed row: a claim that rolls back puts back whatever
 * token it displaced, and a release forgets its token only once the tombstone commits.
 */
@Component
public class AiRunLeaseRegistry {

    private final Map<AiRunLeaseKey, AiRunLease> leases = new ConcurrentHashMap<>();

    /**
     * Records the token this instance will offer for one subject.
     *
     * <p>The returned action undoes exactly this registration, for a claim whose transaction does
     * not commit. MySQL has then rolled the row back to the epoch the displaced token names, and
     * this instance's heartbeat for that epoch may still be renewing it, so the displaced token is
     * put back: a bare removal would leave a live lease whose terminal write finds nothing to
     * tombstone.
     *
     * <p>The undo matches its own token by identity, not by value. The row lock is released before
     * a completion callback runs, so another claim on this instance can commit the same epoch first
     * and register a token that is equal to the rolled-back one while being a different, valid
     * claim. That registration must survive the undo.
     *
     * @param lease the freshly claimed lease
     * @return the action that undoes this registration and restores whatever it displaced
     */
    public Runnable register(AiRunLease lease) {
        Objects.requireNonNull(lease, "lease");
        AiRunLease displaced = leases.put(lease.key(), lease);
        return () -> leases.compute(
                lease.key(), (key, current) -> current == lease ? displaced : current);
    }

    /**
     * Returns the token this instance currently offers for one subject.
     *
     * @param key the lease key
     * @return the held token, or empty when this instance holds none
     */
    public Optional<AiRunLease> find(AiRunLeaseKey key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(leases.get(key));
    }

    /**
     * Forgets one token, leaving any newer token for the same key in place.
     *
     * <p>The token is matched as well as the key so that a late completion callback cannot drop a
     * fresher claim's token — a re-claim of the same subject registers before the previous claim's
     * transaction has finished unwinding.
     *
     * @param lease the token to forget
     */
    public void forget(AiRunLease lease) {
        Objects.requireNonNull(lease, "lease");
        leases.remove(lease.key(), lease);
    }
}
