package ooo.klae.connex.backend.ai.lease;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * One run's ownership flag, polled by its worker at the checkpoints it already reaches.
 *
 * <p>It trips two ways. The first is an authoritative stop signal from the heartbeat: the lease was
 * lost, or the subject is no longer in a state its owner may act on. The second is the owner-side
 * self-fence — the monotonic gap since the last successful renewal has exceeded the lease lifetime,
 * so this instance can no longer prove it still owns the run. The self-fence reads
 * {@link System#nanoTime()} rather than a wall clock, and it can only stop the owner earlier than
 * the database would, never extend a lease. It exists so that a heartbeat blocked behind a slow
 * query stops its own run instead of letting a settler take over a run that is still writing.
 */
public final class AiRunLeaseGuard {

    /** Stop reason recorded when the heartbeat observed an authoritative loss of the lease. */
    public static final String LEASE_LOST = "lease_lost";
    /** Stop reason recorded when the heartbeat observed the subject was no longer running. */
    public static final String SUBJECT_STOPPED = "subject_stopped";
    /** Stop reason recorded by the owner-side self-fence. */
    public static final String RENEW_GAP = "renew_gap";

    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong lastRenewNanos;
    private final AtomicReference<String> stopReason = new AtomicReference<>();

    /**
     * Creates a guard whose self-fence trips once the lease lifetime elapses without a renewal.
     *
     * @param ttl the configured lease lifetime
     */
    public AiRunLeaseGuard(Duration ttl) {
        this(ttl, System::nanoTime);
    }

    AiRunLeaseGuard(Duration ttl, LongSupplier nanoClock) {
        Objects.requireNonNull(ttl, "ttl");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("AI run lease guard lifetime must be positive");
        }
        this.ttlNanos = ttl.toNanos();
        this.lastRenewNanos = new AtomicLong(nanoClock.getAsLong());
    }

    /** Records that a renewal reached the database and matched this owner's token. */
    public void recordRenewal() {
        lastRenewNanos.set(nanoClock.getAsLong());
    }

    /**
     * Records an authoritative stop signal. The first reason recorded wins.
     *
     * @param reason a short diagnostic reason
     */
    public void stop(String reason) {
        Objects.requireNonNull(reason, "reason");
        stopReason.compareAndSet(null, reason);
    }

    /**
     * Answers whether this owner must stop working on the run.
     *
     * @return {@code true} once an authoritative stop signal arrived or the self-fence tripped
     */
    public boolean isStopped() {
        if (stopReason.get() != null) {
            return true;
        }
        if (nanoClock.getAsLong() - lastRenewNanos.get() > ttlNanos) {
            stop(RENEW_GAP);
            return true;
        }
        return false;
    }

    /**
     * Returns why this owner stopped.
     *
     * @return the recorded stop reason, or empty while the run still owns its lease
     */
    public Optional<String> reason() {
        return Optional.ofNullable(stopReason.get());
    }
}
