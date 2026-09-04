package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class CspReportRateLimiterTest {

    @Test
    void dropsReportsPastThePerClientCapWithoutThrowing() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(2, 60, clock);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertFalse(limiter.tryAcquire("198.51.100.7"));
    }

    @Test
    void isolatesClientsAndResetsAtTheNextWindow() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(1, 60, clock);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertFalse(limiter.tryAcquire("198.51.100.7"));
        assertTrue(limiter.tryAcquire("198.51.100.8"));

        clock.advanceMillis(60_000);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
    }

    @Test
    void evictsOnlyStaleWindows() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(2, 60, clock);
        limiter.tryAcquire("198.51.100.7");
        clock.advanceMillis(40_000);
        limiter.tryAcquire("198.51.100.8");
        clock.advanceMillis(20_000);

        limiter.evictStale();

        assertEquals(1, limiter.trackedKeys());
        assertTrue(limiter.tryAcquire("198.51.100.8"));
        assertFalse(limiter.tryAcquire("198.51.100.8"));
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
