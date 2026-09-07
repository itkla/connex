package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Atomic in-memory fixed-window CSP report throttle keyed by resolved client address.
 *
 * <p>Unlike the authenticated client-error throttle this never throws: the report endpoint answers
 * {@code 204} for every bounded body so that a browser cannot learn anything from the response, and
 * an exhausted window simply drops the report.
 *
 * <p>The key space is unauthenticated, so a source rotating through addresses could otherwise grow
 * the window map without bound between evictions. The number of tracked addresses is capped: once
 * the cap is reached an address that has no window is refused until {@link #evictStale()} frees
 * capacity, while addresses already tracked keep their allowance.
 *
 * <p>That cap bounds memory; it is deliberately not an exact ceiling. The admission check reads the
 * map size while {@code compute} holds only the incoming key's bin, so first sightings of distinct
 * addresses that race can each observe the last free slot. The overshoot is bounded by the number
 * of requests admitted concurrently — the container's request-thread count — and each entry is one
 * address string and a two-field record, so the worst case stays within a few percent of the cap.
 * Reserving capacity atomically across keys would trade that for a counter that has to stay exactly
 * in step with the map through every eviction; leaking a reservation there fails closed and refuses
 * every new address until restart, which is a worse outcome than a slightly soft bound.
 */
@Component
public class CspReportRateLimiter {
    private final int maxReports;
    private final long windowMillis;
    private final int maxTrackedClients;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public CspReportRateLimiter(
            @Value("${connex.csp-reports.max-reports-per-window:60}") int maxReports,
            @Value("${connex.csp-reports.window-seconds:60}") long windowSeconds,
            @Value("${connex.csp-reports.max-tracked-clients:10000}") int maxTrackedClients,
            Clock clock) {
        if (maxReports <= 0 || windowSeconds <= 0 || maxTrackedClients <= 0) {
            throw new IllegalArgumentException("CSP report rate-limit settings must be positive");
        }
        this.maxReports = maxReports;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.maxTrackedClients = maxTrackedClients;
        this.clock = clock;
    }

    /**
     * Consumes one report allowance for a client address.
     *
     * @param key the resolved client address
     * @return whether the current window still had an allowance and, for an address not yet
     *     tracked, whether the tracked-address cap left room for it (approximately — see the class
     *     documentation)
     */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (address, existing) -> {
            if (existing == null && windows.size() >= maxTrackedClients) {
                return null;
            }
            if (existing == null || elapsed(now, existing.startedAtMillis()) >= windowMillis) {
                accepted.set(true);
                return new Window(now, 1);
            }
            if (existing.count() >= maxReports) {
                return existing;
            }
            accepted.set(true);
            return new Window(existing.startedAtMillis(), existing.count() + 1);
        });
        return accepted.get();
    }

    /**
     * Drops expired client windows.
     */
    @Scheduled(fixedDelayString = "${connex.csp-reports.eviction-delay-ms:300000}")
    public void evictStale() {
        long now = clock.millis();
        windows.forEach((address, window) -> {
            if (elapsed(now, window.startedAtMillis()) >= windowMillis) {
                windows.remove(address, window);
            }
        });
    }

    int trackedKeys() {
        return windows.size();
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private record Window(long startedAtMillis, int count) {
    }
}
