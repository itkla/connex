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
 * so this instance can no longer prove it still owns the run.
 *
 * <p>The self-fence reads {@link System#nanoTime()} rather than a wall clock, and it is anchored on
 * the instant a lease write was <em>issued</em>, not the instant it returned: the database writes
 * its deadline part-way through that round trip, so anchoring on completion would put the local
 * deadline after the database's and leave a window in which a settler could take the run over while
 * this guard still reported the owner healthy. Anchoring on issue makes the local deadline at or
 * before the database's, which is the direction the fence must err. It exists so that a heartbeat
 * blocked behind a slow query stops its own run instead of letting a settler take over a run that
 * is still writing.
 *
 * <p>The same rule governs both writes that extend a lease: the claim anchors this guard just
 * before it issues the acquiring statement, and every renewal re-anchors it just before its own.
 * Nothing else may move the anchor — in particular not the moment a worker gets round to starting
 * its heartbeat, which can be arbitrarily later than the write it is starting a heartbeat for.
 */
public final class AiRunLeaseGuard {

    /** Stop reason recorded when the heartbeat observed an authoritative loss of the lease. */
    public static final String LEASE_LOST = "lease_lost";
    /** Stop reason recorded when the heartbeat observed the subject was no longer running. */
    public static final String SUBJECT_STOPPED = "subject_stopped";
    /** Stop reason recorded by the owner-side self-fence. */
    public static final String RENEW_GAP = "renew_gap";
    /** Stop reason recorded when no handler owns the leased subject's kind. */
    public static final String NO_SUBJECT_HANDLER = "no_subject_handler";

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

    /**
     * Reads this guard's monotonic clock so a lease write can be stamped before it is issued.
     *
     * @return the current monotonic reading
     */
    public long clockNanos() {
        return nanoClock.getAsLong();
    }

    /**
     * Records that a lease write issued at {@code issuedAtNanos} — the claim's or a renewal's —
     * reached the database and gave this owner a fresh lifetime.
     *
     * @param issuedAtNanos the {@link #clockNanos()} reading taken before the write was issued
     */
    public void recordRenewal(long issuedAtNanos) {
        lastRenewNanos.accumulateAndGet(issuedAtNanos, Math::max);
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
