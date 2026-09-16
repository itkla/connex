package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Verifies the per-actor fixed-window throttle behind administrator account creation: it blocks
 * past the cap so one member manager cannot emit unbounded verification mail, keeps each actor's
 * budget separate, resets once the window elapses, and prunes only elapsed windows on a sweep that
 * is actually scheduled.
 */
class AccountCreationRateLimiterTest {

    @Test
    void blocksAfterCapWithinWindow() {
        AccountCreationRateLimiter limiter = new AccountCreationRateLimiter(3, 900, new MutableClock());

        assertTrue(limiter.tryAcquire(7));
        assertTrue(limiter.tryAcquire(7));
        assertTrue(limiter.tryAcquire(7));
        assertFalse(limiter.tryAcquire(7), "the fourth creation in-window must be blocked");
    }

    @Test
    void isolatesByActor() {
        AccountCreationRateLimiter limiter = new AccountCreationRateLimiter(1, 900, new MutableClock());

        assertTrue(limiter.tryAcquire(7));
        assertFalse(limiter.tryAcquire(7));
        assertTrue(limiter.tryAcquire(8), "another administrator has its own budget");
    }

    @Test
    void resetsAfterWindowElapses() {
        MutableClock clock = new MutableClock();
        AccountCreationRateLimiter limiter = new AccountCreationRateLimiter(1, 900, clock);

        assertTrue(limiter.tryAcquire(7));
        assertFalse(limiter.tryAcquire(7));
        clock.advanceMillis(900_000);
        assertTrue(limiter.tryAcquire(7), "budget refreshes once the window passes");
    }

    @Test
    void evictsOnlyStaleWindows() {
        MutableClock clock = new MutableClock();
        AccountCreationRateLimiter limiter = new AccountCreationRateLimiter(2, 900, clock);
        limiter.tryAcquire(7);
        clock.advanceMillis(600_000);
        limiter.tryAcquire(8);
        clock.advanceMillis(300_000);

        limiter.evictStale();

        assertEquals(1, limiter.trackedWindows(), "the elapsed window must be dropped");
        assertTrue(limiter.tryAcquire(8));
        assertFalse(
                limiter.tryAcquire(8),
                "the surviving window must keep the allowance it had already consumed");
    }

    @Test
    void evictionIsScheduled() throws NoSuchMethodException {
        Scheduled scheduled = AccountCreationRateLimiter.class
                .getMethod("evictStale")
                .getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "an unscheduled sweep leaves the window map unbounded");
        assertEquals(
                "${connex.account-creation.eviction-delay-ms:900000}",
                scheduled.fixedDelayString());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
