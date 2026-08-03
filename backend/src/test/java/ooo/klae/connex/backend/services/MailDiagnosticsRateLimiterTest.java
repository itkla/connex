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
 * Verifies the per-workspace-and-actor fixed-window throttle: it blocks past the cap, keeps each
 * pair's budget separate, resets once the window elapses, and prunes only elapsed windows on a
 * sweep that is actually scheduled.
 */
class MailDiagnosticsRateLimiterTest {

    @Test
    void blocksAfterCapWithinWindow() {
        MailDiagnosticsRateLimiter limiter = new MailDiagnosticsRateLimiter(3, 300, new MutableClock());
        long now = 1_000L;

        assertTrue(limiter.tryAcquire(41, 7, now));
        assertTrue(limiter.tryAcquire(41, 7, now));
        assertTrue(limiter.tryAcquire(41, 7, now));
        assertFalse(limiter.tryAcquire(41, 7, now), "the fourth send in-window must be blocked");
    }

    @Test
    void isolatesByWorkspaceAndActor() {
        MailDiagnosticsRateLimiter limiter = new MailDiagnosticsRateLimiter(1, 300, new MutableClock());
        long now = 1_000L;

        assertTrue(limiter.tryAcquire(41, 7, now));
        assertFalse(limiter.tryAcquire(41, 7, now));
        assertTrue(limiter.tryAcquire(42, 7, now), "another workspace has its own budget");
        assertTrue(limiter.tryAcquire(41, 8, now), "another actor has its own budget");
    }

    @Test
    void resetsAfterWindowElapses() {
        MailDiagnosticsRateLimiter limiter = new MailDiagnosticsRateLimiter(1, 300, new MutableClock());
        long start = 1_000L;

        assertTrue(limiter.tryAcquire(41, 7, start));
        assertFalse(limiter.tryAcquire(41, 7, start));
        assertTrue(limiter.tryAcquire(41, 7, start + 300_000L), "budget refreshes once the window passes");
    }

    @Test
    void evictsOnlyStaleWindows() {
        MutableClock clock = new MutableClock();
        MailDiagnosticsRateLimiter limiter = new MailDiagnosticsRateLimiter(2, 300, clock);
        limiter.tryAcquire(41, 7, clock.millis());
        clock.advanceMillis(200_000);
        limiter.tryAcquire(42, 8, clock.millis());
        clock.advanceMillis(100_000);

        limiter.evictStale();

        assertEquals(1, limiter.trackedWindows(), "the elapsed window must be dropped");
        assertTrue(limiter.tryAcquire(42, 8, clock.millis()));
        assertFalse(
                limiter.tryAcquire(42, 8, clock.millis()),
                "the surviving window must keep the allowance it had already consumed");
    }

    @Test
    void evictionIsScheduled() throws NoSuchMethodException {
        Scheduled scheduled = MailDiagnosticsRateLimiter.class
                .getMethod("evictStale")
                .getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "an unscheduled sweep leaves the window map unbounded");
        assertEquals(
                "${connex.mail.diagnostics.eviction-delay-ms:300000}",
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
