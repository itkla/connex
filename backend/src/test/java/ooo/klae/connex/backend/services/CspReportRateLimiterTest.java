package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class CspReportRateLimiterTest {

    @Test
    void dropsReportsPastThePerClientCapWithoutThrowing() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(2, 60, 100, clock);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertFalse(limiter.tryAcquire("198.51.100.7"));
    }

    @Test
    void isolatesClientsAndResetsAtTheNextWindow() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(1, 60, 100, clock);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
        assertFalse(limiter.tryAcquire("198.51.100.7"));
        assertTrue(limiter.tryAcquire("198.51.100.8"));

        clock.advanceMillis(60_000);

        assertTrue(limiter.tryAcquire("198.51.100.7"));
    }

    @Test
    void evictsOnlyStaleWindows() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(2, 60, 100, clock);
        limiter.tryAcquire("198.51.100.7");
        clock.advanceMillis(40_000);
        limiter.tryAcquire("198.51.100.8");
        clock.advanceMillis(20_000);

        limiter.evictStale();

        assertEquals(1, limiter.trackedKeys());
        assertTrue(limiter.tryAcquire("198.51.100.8"));
        assertFalse(limiter.tryAcquire("198.51.100.8"));
    }

    /**
     * A source rotating through addresses must not be able to lock the collector to its own keys:
     * at the cap the least recently updated window makes way and the newcomer is still heard.
     */
    @Test
    void admitsUnseenClientsAtTheTrackedCapByEvictingTheLeastRecentlyUpdatedWindow() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(2, 60, 2, clock);
        assertTrue(limiter.tryAcquire("2001:db8::1"));
        clock.advanceMillis(1_000);
        assertTrue(limiter.tryAcquire("2001:db8::2"));
        clock.advanceMillis(1_000);
        assertTrue(limiter.tryAcquire("2001:db8::1"));

        clock.advanceMillis(1_000);

        assertTrue(limiter.tryAcquire("2001:db8::3"));
        assertEquals(2, limiter.trackedKeys());
        assertFalse(limiter.tracks("2001:db8::2"));
        assertTrue(limiter.tracks("2001:db8::1"));
        assertTrue(limiter.tracks("2001:db8::3"));
        assertFalse(limiter.tryAcquire("2001:db8::1"));
    }

    /**
     * Saturation is a different operational condition from ordinary throttling, so it is visible
     * once per window rather than on every refused-capacity request.
     */
    @Test
    void logsTrackedAddressSaturationOncePerWindow() {
        MutableClock clock = new MutableClock();
        CspReportRateLimiter limiter = new CspReportRateLimiter(5, 60, 1, clock);
        Logger logger = (Logger) LoggerFactory.getLogger(CspReportRateLimiter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            limiter.tryAcquire("198.51.100.1");
            assertTrue(appender.list.isEmpty());

            limiter.tryAcquire("198.51.100.2");
            clock.advanceMillis(1_000);
            limiter.tryAcquire("198.51.100.3");

            assertEquals(1, appender.list.size());
            assertEquals(Level.WARN, appender.list.getFirst().getLevel());
            assertTrue(appender.list.getFirst().getFormattedMessage()
                    .startsWith("csp.report.throttle.saturated"));

            clock.advanceMillis(60_000);
            limiter.tryAcquire("198.51.100.4");

            assertEquals(2, appender.list.size());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
