package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fixed-window JVM-local admission for access-denial audit rows, keyed by user, audit action and
 * client address (#1850).
 *
 * <p>A filter that records a denial before refusing a {@code GET} can be driven from another site. The
 * session cookie rides a cross-site top-level navigation under {@code SameSite=Lax}, and any cross-site
 * subresource load under the {@code SameSite=None} that SAML deployments use. Every visit would otherwise
 * append another row attributed to the victim and advance the shared integrity head. One row per window
 * still records the fact of the denial. The client address is part of the key because a forged trigger
 * arrives from the victim's own browser, or from the frontend server rendering a page for it, while a
 * stolen session used from anywhere else must keep leaving its own evidence through its own requests. The addresses admitted per user and action are
 * capped, so rotating source addresses cannot reopen the volume the window bounds while the tracked
 * key set stays below its limit.
 *
 * <p>The bound is per replica, and the address is only as distinct as {@link
 * ooo.klae.connex.backend.util.ClientIpResolver} makes it: behind a proxy that is not configured as
 * trusted, every request resolves to the proxy and the key narrows to user and action.
 *
 * <p>A suppressed row leaves no audit evidence, so suppression is counted instead:
 * {@code connex.security.denial.audit.suppressed} per action, and
 * {@code connex.security.denial.audit.evictions} for live windows dropped to bound the key set.
 * Operators can then see a forged flood or a saturated limiter.
 */
@Component
public class DenialAuditRateLimiter {
    static final int MAX_ADDRESSES_PER_DENIAL = 8;
    static final int MAX_TRACKED_DENIALS = 4_096;
    static final String SUPPRESSED_METRIC = "connex.security.denial.audit.suppressed";
    static final String EVICTED_METRIC = "connex.security.denial.audit.evictions";
    private static final String UNRESOLVED_ADDRESS = "";
    private static final Comparator<Map.Entry<Key, Window>> NEWEST_ADMISSION_FIRST = Comparator
            .comparingLong((Map.Entry<Key, Window> entry) -> entry.getValue().latestAdmissionMillis())
            .reversed();

    private final long windowMillis;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Counter evictions;
    private final ConcurrentHashMap<Key, Window> windows = new ConcurrentHashMap<>();

    /**
     * An admitted audit row, which the caller hands back through {@link #release} when it could not
     * be written.
     *
     * @param userId denied user
     * @param action audit action the row records
     * @param clientAddress client address the row was admitted for, empty when none was resolved
     * @param admittedAtMillis when the admission opened its window
     */
    public record Admission(int userId, String action, String clientAddress, long admittedAtMillis) {
    }

    public DenialAuditRateLimiter(
            @Value("${connex.security.denial-audit-window-seconds:3600}") long windowSeconds,
            Clock clock,
            MeterRegistry meterRegistry) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("Denial audit window must be positive");
        }
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.evictions = meterRegistry.counter(EVICTED_METRIC);
    }

    /**
     * Admits at most one audit row for a user, action and client address during the window, and at
     * most eight addresses for a user and action.
     *
     * @param userId denied user
     * @param action audit action the row would record
     * @param clientAddress resolved client address, or null when none could be resolved
     * @return the admission when the caller may append the audit row, otherwise empty
     */
    public Optional<Admission> acquire(int userId, String action, String clientAddress) {
        Key key = new Key(userId, action);
        String address = Objects.requireNonNullElse(clientAddress, UNRESOLVED_ADDRESS);
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (existingKey, existing) -> {
            List<Admitted> live = existing == null ? List.of() : existing.liveAt(now, windowMillis);
            if (live.size() >= MAX_ADDRESSES_PER_DENIAL
                    || live.stream().anyMatch(admitted -> admitted.address().equals(address))) {
                return new Window(live);
            }
            accepted.set(true);
            List<Admitted> next = new ArrayList<>(live);
            next.add(new Admitted(address, now));
            return new Window(List.copyOf(next));
        });
        if (windows.size() > MAX_TRACKED_DENIALS) {
            evict(now);
        }
        if (!accepted.get()) {
            meterRegistry.counter(SUPPRESSED_METRIC, "action", action).increment();
            return Optional.empty();
        }
        return Optional.of(new Admission(userId, action, address, now));
    }

    /**
     * Hands back an admission whose audit row could not be written, so the next denial records
     * instead of the failure suppressing evidence for the rest of the window. Only the exact window
     * the admission opened is dropped, never a newer one for the same address.
     *
     * @param admission the admission {@link #acquire} returned
     */
    public void release(Admission admission) {
        windows.computeIfPresent(new Key(admission.userId(), admission.action()), (key, existing) -> {
            List<Admitted> remaining = existing.admitted().stream()
                    .filter(admitted -> !admitted.address().equals(admission.clientAddress())
                            || admitted.admittedAtMillis() != admission.admittedAtMillis())
                    .toList();
            return remaining.isEmpty() ? null : new Window(remaining);
        });
    }

    /**
     * Bounds the tracked key set, dropping fully expired denials before the least recently admitted
     * live ones.
     *
     * <p>Evicting a live key re-arms it: each of its tracked addresses may record one more row early.
     * While more keys than the limit stay live, the oldest can be evicted again and again, so the
     * per-key bound only holds below that limit. Losing evidence is the worse failure, so the limit is
     * enforced by dropping the oldest suppression rather than by refusing to admit a row.
     *
     * <p>Once saturated this runs for every new key, so it stays one pass over the cached latest
     * admission of each window: the excess oldest are kept in a heap of that size, which is a single
     * minimum scan for the usual excess of one.
     */
    private void evict(long now) {
        windows.forEach((key, window) -> {
            if (window.expiredAt(now, windowMillis)) {
                windows.remove(key, window);
            }
        });
        int excess = windows.size() - MAX_TRACKED_DENIALS;
        if (excess <= 0) {
            return;
        }
        PriorityQueue<Map.Entry<Key, Window>> oldest = new PriorityQueue<>(excess, NEWEST_ADMISSION_FIRST);
        for (Map.Entry<Key, Window> entry : windows.entrySet()) {
            oldest.offer(entry);
            if (oldest.size() > excess) {
                oldest.poll();
            }
        }
        for (Map.Entry<Key, Window> entry : oldest) {
            if (windows.remove(entry.getKey(), entry.getValue())) {
                evictions.increment();
            }
        }
    }

    int trackedDenials() {
        return windows.size();
    }

    /**
     * Returns the milliseconds since {@code startedAt}: zero for a start in the future, and saturated
     * rather than wrapped for one beyond the {@code long} range. It therefore never decreases as the
     * start moves earlier, which is what lets a window's expiry be decided by its latest admission.
     */
    private static long elapsed(long now, long startedAt) {
        if (startedAt >= now) {
            return 0L;
        }
        long elapsed = now - startedAt;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private record Key(int userId, String action) {
    }

    private record Admitted(String address, long admittedAtMillis) {
    }

    private record Window(List<Admitted> admitted, long latestAdmissionMillis) {
        private Window(List<Admitted> admitted) {
            this(admitted,
                    admitted.stream().mapToLong(Admitted::admittedAtMillis).max().orElse(Long.MIN_VALUE));
        }

        private List<Admitted> liveAt(long now, long windowMillis) {
            return admitted.stream()
                    .filter(entry -> elapsed(now, entry.admittedAtMillis()) < windowMillis)
                    .toList();
        }

        private boolean expiredAt(long now, long windowMillis) {
            return admitted.isEmpty() || elapsed(now, latestAdmissionMillis) >= windowMillis;
        }
    }
}
