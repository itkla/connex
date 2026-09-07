package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * the window map without bound between evictions. The number of tracked addresses is therefore
 * capped — but admission stays fair: an address the throttle has never seen is <strong>never</strong>
 * refused for want of capacity. When the map is full, the least recently updated window is dropped
 * to make room. Refusing newcomers instead would let one rotating source hold every slot hostage:
 * each of its requests refreshes its own window, {@link #evictStale()} then frees nothing, and the
 * collector goes blind to every other client — including the browser reporting a live XSS.
 *
 * <p>What the cap does <em>not</em> buy under saturation is per-client isolation. A client whose
 * window is evicted starts its next report on a fresh allowance, so a source rotating through more
 * than {@code max-tracked-clients} addresses can raise the throttle's effective ceiling. That is the
 * deliberate trade: bounded memory and a collector that still hears from everyone, rather than an
 * exact per-client ceiling and a collector one source can silence. Reaching the cap is logged once
 * per window at {@code WARN} so saturation is visible to operators rather than indistinguishable
 * from ordinary throttling.
 *
 * <p>The cap bounds memory; it is deliberately not an exact ceiling. The capacity check and the
 * insert are separate steps, so first sightings of distinct addresses that race can each pass it.
 * The overshoot is bounded by the number of requests admitted concurrently — the container's
 * request-thread count — and each entry is one address string and a three-field record, so the
 * worst case stays within a few percent of the cap. Reserving capacity atomically across keys would
 * trade that for a counter that has to stay exactly in step with the map through every eviction;
 * leaking a reservation there refuses new addresses until restart, which is a worse outcome than a
 * slightly soft bound.
 */
@Component
public class CspReportRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(CspReportRateLimiter.class);
    private static final long NEVER = Long.MIN_VALUE;

    private final int maxReports;
    private final long windowMillis;
    private final int maxTrackedClients;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong saturationLoggedAtMillis = new AtomicLong(NEVER);

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
     * <p>An address that is not yet tracked is always admitted; if that would exceed the
     * tracked-address cap, the least recently updated window is evicted first.
     *
     * @param key the resolved client address
     * @return whether the client's current window still had an allowance
     */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        makeRoomFor(key, now);
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (address, existing) -> {
            if (existing == null || elapsed(now, existing.startedAtMillis()) >= windowMillis) {
                accepted.set(true);
                return new Window(now, now, 1);
            }
            if (existing.count() >= maxReports) {
                return new Window(existing.startedAtMillis(), now, existing.count());
            }
            accepted.set(true);
            return new Window(existing.startedAtMillis(), now, existing.count() + 1);
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

    boolean tracks(String key) {
        return windows.containsKey(key);
    }

    private void makeRoomFor(String key, long now) {
        if (windows.size() < maxTrackedClients || windows.containsKey(key)) {
            return;
        }
        logSaturation(now);
        evictLeastRecentlyUpdated();
    }

    private void evictLeastRecentlyUpdated() {
        Map.Entry<String, Window> oldest = null;
        for (Map.Entry<String, Window> candidate : windows.entrySet()) {
            if (oldest == null
                    || candidate.getValue().lastSeenAtMillis() < oldest.getValue().lastSeenAtMillis()) {
                oldest = candidate;
            }
        }
        if (oldest != null) {
            windows.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private void logSaturation(long now) {
        long loggedAt = saturationLoggedAtMillis.get();
        if (loggedAt != NEVER && elapsed(now, loggedAt) < windowMillis) {
            return;
        }
        if (saturationLoggedAtMillis.compareAndSet(loggedAt, now)) {
            log.warn("csp.report.throttle.saturated trackedClients={}", maxTrackedClients);
        }
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private record Window(long startedAtMillis, long lastSeenAtMillis, int count) {
    }
}
