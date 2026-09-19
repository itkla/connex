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
     * Forgets the token for one subject.
     *
     * @param key the lease key
     */
    public void forget(AiRunLeaseKey key) {
        Objects.requireNonNull(key, "key");
        leases.remove(key);
    }
}
