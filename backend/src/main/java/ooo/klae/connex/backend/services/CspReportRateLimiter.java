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
 */
@Component
public class CspReportRateLimiter {
    private final int maxReports;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public CspReportRateLimiter(
            @Value("${connex.csp-reports.max-reports-per-window:60}") int maxReports,
            @Value("${connex.csp-reports.window-seconds:60}") long windowSeconds,
            Clock clock) {
        if (maxReports <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("CSP report rate-limit settings must be positive");
        }
        this.maxReports = maxReports;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.clock = clock;
    }

    /**
     * Consumes one report allowance for a client address.
     *
     * @param key the resolved client address
     * @return whether the current window still had an allowance
     */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(key, (address, existing) -> {
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
