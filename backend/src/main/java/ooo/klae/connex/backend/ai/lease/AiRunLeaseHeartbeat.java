package ooo.klae.connex.backend.ai.lease;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Keeps one instance's run leases alive and observes cross-instance stop signals.
 *
 * <p>Each tick runs inside {@link TenantWorkScope#inWorkspace(int, java.util.function.Supplier)} so
 * background work routes to the lease's own catalog, reads the subject's liveness through
 * {@link AiRunLeaseSubjectHandler}, and only then renews. A renewal that throws is unknown, not
 * lost: it is logged and retried, and if the outage outlasts the lease lifetime the lease expires
 * and a settler takes the run over — the correct outcome for an owner that cannot reach the
 * database. An authoritative loss, a subject that is no longer running, or a subject kind no
 * handler owns stops the schedule.
 *
 * <p>Ticks run at a fixed rate measured from the claim, not at a fixed delay after the previous
 * tick returns. A renewal that fails slowly — a statement that waits out its timeout — would
 * otherwise push the next attempt a whole interval past the moment the failure returned, which with
 * three beats per lifetime lets one slow failure carry the retry beyond the deadline even though
 * the outage was far shorter than the lease. At a fixed rate the overdue tick runs as soon as the
 * slow one returns. Ticks of one lease never overlap, and a tick that finds the guard already
 * stopped returns without touching the database, so the catch-up after a long stall is cheap.
 *
 * <p>A missing handler fails closed in both directions: {@link #start(AiRunLease, AiRunLeaseGuard)}
 * refuses to begin heartbeating a subject kind nothing can answer for, and a tick that somehow
 * reaches one stops the run rather than renewing blind. Renewing without a liveness read would
 * leave the documented cross-instance stop bound silently absent for that kind.
 *
 * <p>The pool is sized from {@code run-lease-heartbeat-threads}, validated to cover the generation
 * worker count, so one slow renewal cannot head-of-line-block another run's tick.
 */
@Component
public class AiRunLeaseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(AiRunLeaseHeartbeat.class);

    private final AiRunLeaseService leaseService;
    private final TenantWorkScope tenantWorkScope;
    private final Map<AiRunLeaseSubject, AiRunLeaseSubjectHandler> handlers =
            new EnumMap<>(AiRunLeaseSubject.class);
    private final ScheduledThreadPoolExecutor scheduler;
    private final long intervalMillis;

    /**
     * Creates the heartbeat pool and indexes the declared subject handlers.
     *
     * @param leaseService the lease transactional boundary
     * @param tenantWorkScope tenant routing for off-request work
     * @param properties instance-wide AI configuration
     * @param subjectHandlers every declared subject handler
     */
    public AiRunLeaseHeartbeat(
            AiRunLeaseService leaseService,
            TenantWorkScope tenantWorkScope,
            AiProperties properties,
            List<AiRunLeaseSubjectHandler> subjectHandlers) {
        this.leaseService = leaseService;
        this.tenantWorkScope = tenantWorkScope;
        for (AiRunLeaseSubjectHandler handler : subjectHandlers) {
            AiRunLeaseSubjectHandler previous = handlers.put(handler.subject(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI run lease subject handler for " + handler.subject());
            }
        }
        this.intervalMillis = properties.getRunLeaseHeartbeatInterval().toMillis();
        this.scheduler = new ScheduledThreadPoolExecutor(
                properties.getRunLeaseHeartbeatThreads(),
                Thread.ofPlatform().daemon().name("connex-ai-lease-heartbeat-", 0).factory());
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /**
     * Starts renewing one lease until the returned handle is closed or ownership is lost.
     *
     * <p>Starting deliberately does not touch the guard's anchor. The claim already anchored it on
     * the instant it issued the lease write ({@link
     * AiRunLeaseService#acquireInCurrentTransaction(AiRunLeaseKey, AiRunLeaseGuard)}), which is at
     * or before every deadline MySQL can have assigned. Re-anchoring here would move the local
     * fence to a reading taken after the write — after the commit, and after however long this
     * worker was descheduled on the way to this line — so a worker paused past the lifetime would
     * report itself healthy over a lease a settler was already entitled to take, which is the
     * exact failure the fence exists to prevent.
     *
     * @param lease the fencing token to keep alive
     * @param guard the owner's ownership flag, anchored by the claim and fed by every tick
     * @return a handle that cancels the schedule
     */
    public AutoCloseable start(AiRunLease lease, AiRunLeaseGuard guard) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(guard, "guard");
        if (!handlers.containsKey(lease.key().subject())) {
            throw new IllegalStateException(
                    "No AI run lease subject handler is registered for "
                            + lease.key().subject()
                            + ", so this instance could not observe a cross-instance stop signal"
                            + " for the run it is about to heartbeat");
        }
        AtomicReference<ScheduledFuture<?>> handle = new AtomicReference<>();
        ScheduledFuture<?> tick = scheduler.scheduleAtFixedRate(
                () -> {
                    if (beat(lease, guard)) {
                        cancel(handle);
                    }
                },
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
        handle.set(tick);
        if (guard.isStopped()) {
            cancel(handle);
        }
        return () -> cancel(handle);
    }

    boolean beat(AiRunLease lease, AiRunLeaseGuard guard) {
        if (guard.isStopped()) {
            return true;
        }
        AiRunLeaseKey key = lease.key();
        long issuedAt = guard.clockNanos();
        try {
            Optional<String> stop = tenantWorkScope.inWorkspace(
                    key.workspaceId(), () -> observe(lease));
            if (stop.isEmpty()) {
                guard.recordRenewal(issuedAt);
                return false;
            }
            guard.stop(stop.get());
            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "AI run lease heartbeat failed for workspace {} subject {} {}",
                    key.workspaceId(),
                    key.subject().wireKey(),
                    key.subjectId(),
                    exception);
            return false;
        }
    }

    private static void cancel(AtomicReference<ScheduledFuture<?>> handle) {
        ScheduledFuture<?> tick = handle.get();
        if (tick != null) {
            tick.cancel(false);
        }
    }

    private Optional<String> observe(AiRunLease lease) {
        AiRunLeaseKey key = lease.key();
        AiRunLeaseSubjectHandler handler = handlers.get(key.subject());
        if (handler == null) {
            log.error(
                    "No AI run lease subject handler for {}; stopping the run rather than renewing"
                            + " a lease whose stop signal this instance cannot observe",
                    key.subject().wireKey());
            return Optional.of(AiRunLeaseGuard.NO_SUBJECT_HANDLER);
        }
        if (!handler.isSubjectRunning(key.workspaceId(), key.subjectId())) {
            return Optional.of(AiRunLeaseGuard.SUBJECT_STOPPED);
        }
        if (leaseService.renew(lease) == AiRunLeaseOutcome.LOST) {
            return Optional.of(AiRunLeaseGuard.LEASE_LOST);
        }
        return Optional.empty();
    }

    /**
     * Returns the fixed heartbeat thread count, so the sizing rule that keeps one slow renewal from
     * head-of-line-blocking another run's tick is assertable rather than assumed.
     *
     * @return the heartbeat pool's thread count
     */
    int poolSize() {
        return scheduler.getCorePoolSize();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
