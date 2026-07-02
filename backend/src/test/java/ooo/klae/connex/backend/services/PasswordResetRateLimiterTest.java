package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the per-IP fixed-window throttle: it blocks past the cap, isolates by IP, resets after
 * the window elapses, and never blocks unattributable (null/blank) callers.
 */
class PasswordResetRateLimiterTest {

    @Test
    void blocksAfterCapWithinWindow() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(3, 900);
        long now = 1_000L;

        assertTrue(limiter.tryAcquire("1.1.1.1", now));
        assertTrue(limiter.tryAcquire("1.1.1.1", now));
        assertTrue(limiter.tryAcquire("1.1.1.1", now));
        assertFalse(limiter.tryAcquire("1.1.1.1", now), "the fourth request in-window must be blocked");
    }

    @Test
    void isolatesByIp() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(1, 900);
        long now = 1_000L;

        assertTrue(limiter.tryAcquire("1.1.1.1", now));
        assertFalse(limiter.tryAcquire("1.1.1.1", now));
        assertTrue(limiter.tryAcquire("2.2.2.2", now), "a different IP has its own budget");
    }

    @Test
    void resetsAfterWindowElapses() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(1, 900);
        long start = 1_000L;

        assertTrue(limiter.tryAcquire("1.1.1.1", start));
        assertFalse(limiter.tryAcquire("1.1.1.1", start));
        assertTrue(limiter.tryAcquire("1.1.1.1", start + 900_000L), "budget refreshes once the window passes");
    }

    @Test
    void allowsUnattributableCallers() {
        PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(1, 900);
        assertTrue(limiter.tryAcquire(null, 1_000L));
        assertTrue(limiter.tryAcquire("", 1_000L));
        assertTrue(limiter.tryAcquire("   ", 1_000L));
    }
}
