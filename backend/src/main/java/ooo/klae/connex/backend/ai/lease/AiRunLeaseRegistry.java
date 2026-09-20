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
 * so this map cannot disagree with the committed row: a claim that rolls back removes its entry
 * again, and a release forgets its token only once the tombstone commits.
 */
@Component
public class AiRunLeaseRegistry {

    private final Map<AiRunLeaseKey, AiRunLease> leases = new ConcurrentHashMap<>();

    /**
     * Records the token this instance will offer for one subject.
     *
     * @param lease the freshly claimed lease
     */
    public void register(AiRunLease lease) {
        Objects.requireNonNull(lease, "lease");
        leases.put(lease.key(), lease);
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
