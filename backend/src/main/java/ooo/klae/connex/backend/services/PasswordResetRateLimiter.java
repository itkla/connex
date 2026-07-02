package ooo.klae.connex.backend.services;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory fixed-window throttle for password reset requests, keyed by client IP. Applied before
 * the account lookup so probing unknown emails is bounded, which keeps the enumeration-safe branch
 * from being cheaply exploitable. Single-JVM only, matching the in-memory session model.
 */
@Component
public class PasswordResetRateLimiter {

    private final int maxPerIp;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public PasswordResetRateLimiter(
            @Value("${connex.password-reset.max-requests-per-ip:20}") int maxPerIp,
            @Value("${connex.password-reset.request-window-seconds:900}") long windowSeconds) {
        this.maxPerIp = maxPerIp;
        this.windowMillis = windowSeconds * 1000L;
    }

    /**
     * Records a request from the given IP and reports whether it is within the allowed window.
     * A null/blank IP cannot be attributed and is allowed through.
     * @param ip the requesting client IP
     * @param nowMillis the current epoch time in milliseconds
     * @return true when the request is under the per-IP cap
     */
    public boolean tryAcquire(String ip, long nowMillis) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        Window window = windows.compute(ip, (key, existing) -> {
            if (existing == null || nowMillis - existing.start >= windowMillis) {
                return new Window(nowMillis, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= maxPerIp;
    }

    /**
     * Drops windows whose period has elapsed, bounding memory growth.
     * @param nowMillis the current epoch time in milliseconds
     */
    public void evictStale(long nowMillis) {
        windows.entrySet().removeIf(entry -> nowMillis - entry.getValue().start >= windowMillis);
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
