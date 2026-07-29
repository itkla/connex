package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class ClientErrorRateLimiterTest {

    @Test
    void rejectsReportsPastThePerUserCap() {
        MutableClock clock = new MutableClock();
        ClientErrorRateLimiter limiter = new ClientErrorRateLimiter(2, 300, clock);

        assertDoesNotThrow(() -> limiter.acquire(7));
        assertDoesNotThrow(() -> limiter.acquire(7));
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(7));
    }

    @Test
    void isolatesUsersAndResetsAtTheNextWindow() {
        MutableClock clock = new MutableClock();
        ClientErrorRateLimiter limiter = new ClientErrorRateLimiter(1, 300, clock);

        limiter.acquire(7);
        assertThrows(TooManyRequestsException.class, () -> limiter.acquire(7));
        assertDoesNotThrow(() -> limiter.acquire(8));

        clock.advanceMillis(300_000);

        assertDoesNotThrow(() -> limiter.acquire(7));
    }

    @Test
    void evictsOnlyStaleWindows() {
        MutableClock clock = new MutableClock();
        ClientErrorRateLimiter limiter = new ClientErrorRateLimiter(2, 300, clock);
        limiter.acquire(7);
        clock.advanceMillis(200_000);
        limiter.acquire(8);
        clock.advanceMillis(100_000);

        limiter.evictStale();

        assertEquals(1, limiter.trackedUsers());
        assertThrows(TooManyRequestsException.class, () -> {
            limiter.acquire(8);
            limiter.acquire(8);
        });
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
