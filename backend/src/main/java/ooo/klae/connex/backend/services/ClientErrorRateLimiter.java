package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

/**
 * Atomic in-memory fixed-window client error throttle keyed by resolved user identifier.
 */
@Component
public class ClientErrorRateLimiter {
    private final int maxReports;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentHashMap<Integer, Window> windows = new ConcurrentHashMap<>();

    public ClientErrorRateLimiter(
            @Value("${connex.client-errors.max-reports-per-window:20}") int maxReports,
            @Value("${connex.client-errors.window-seconds:300}") long windowSeconds,
            Clock clock) {
        if (maxReports <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("Client error rate-limit settings must be positive");
        }
        this.maxReports = maxReports;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1_000L);
        this.clock = clock;
    }

    /**
     * Consumes one report allowance for the resolved user.
     *
     * @param userId resolved user identifier
     * @throws TooManyRequestsException when the current window is exhausted
     */
    public void acquire(int userId) {
        long now = clock.millis();
        AtomicBoolean accepted = new AtomicBoolean();
        windows.compute(userId, (key, existing) -> {
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
        if (!accepted.get()) {
            throw new TooManyRequestsException("Too many client error reports. Please try again later.");
        }
    }

    /**
     * Drops expired user windows.
     */
    @Scheduled(fixedDelayString = "${connex.client-errors.eviction-delay-ms:300000}")
    public void evictStale() {
        long now = clock.millis();
        windows.forEach((userId, window) -> {
            if (elapsed(now, window.startedAtMillis()) >= windowMillis) {
                windows.remove(userId, window);
            }
        });
    }

    int trackedUsers() {
        return windows.size();
    }

    private static long elapsed(long now, long startedAt) {
        return Math.max(0L, now - startedAt);
    }

    private record Window(long startedAtMillis, int count) {
    }
}
