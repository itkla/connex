package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ApiRateLimiterTest {
    private static final byte[] HMAC_KEY =
        "public-api-rate-limiter-test-key".getBytes(StandardCharsets.UTF_8);

    @Test
    void fixedWindowResetsAfterConfiguredDuration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(2, 60, clock);

        assertTrue(limiter.acquire(7).allowed());
        assertTrue(limiter.acquire(7).allowed());
        assertFalse(limiter.acquire(7).allowed());
        clock.advanceSeconds(60);
        assertTrue(limiter.acquire(7).allowed());
    }
    @Test
    void preAuthenticationAdmissionRequiresBothClientAndFullTokenAllowances() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(100, 2, 2, 60, clock, HMAC_KEY);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-b").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.1", "token-c").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.2", "token-a").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.3", "token-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.2", "token-d").allowed());
        clock.advanceSeconds(60);
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-a").allowed());
    }

    @Test
    void displayedLastFourCannotExhaustAnotherTokensBucket() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(100, 100, 1, 60, clock, HMAC_KEY);
        String actual = "cnx_pat_" + "a".repeat(39) + "last";
        String attackerGuess = "cnx_pat_" + "b".repeat(39) + "last";

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", attackerGuess).allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.2", actual).allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.3", actual).allowed());
    }

    @Test
    void expiredWindowsAreReclaimedBeforeCapacityRefusal() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 100, 60, clock, HMAC_KEY, 2, 2, 60);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "attacker-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.2", "attacker-b").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.3", "blocked").allowed());
        assertEquals(2, limiter.preAuthClientWindowCount());
        assertEquals(2, limiter.preAuthTokenWindowCount());

        clock.advanceSeconds(60);

        assertTrue(limiter.acquireBeforeAuthentication("198.51.100.1", "legitimate").allowed());
        assertEquals(1, limiter.preAuthClientWindowCount());
        assertEquals(1, limiter.preAuthTokenWindowCount());
    }

    @Test
    void oneClientCannotCreateUnboundedTokenWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 100, 60, clock, HMAC_KEY, 100, 100, 3);

        for (int index = 0; index < 50; index++) {
            assertTrue(limiter.acquireBeforeAuthentication(
                "192.0.2.1", "attacker-" + index).allowed());
        }

        assertEquals(1, limiter.preAuthClientWindowCount());
        assertEquals(3, limiter.preAuthTokenWindowCount());
        assertTrue(limiter.acquireBeforeAuthentication("198.51.100.1", "legitimate").allowed());
        assertEquals(4, limiter.preAuthTokenWindowCount());
    }

    @Test
    void clientAndTokenNamespacesHaveIndependentCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 100, 60, clock, HMAC_KEY, 1, 3, 60);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-b").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-c").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.2", "token-d").allowed());
        assertEquals(1, limiter.preAuthClientWindowCount());
        assertEquals(3, limiter.preAuthTokenWindowCount());
    }

    @Test
    void sustainedFloodFromFewClientsCannotDisplaceALegitimateClient() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 1_000, 1_000, 60, clock, HMAC_KEY, 100, 100, 5);

        for (int window = 0; window < 2; window++) {
            for (int client = 0; client < 4; client++) {
                for (int request = 0; request < 200; request++) {
                    assertTrue(limiter.acquireBeforeAuthentication(
                        "192.0.2." + client,
                        "attacker-" + window + "-" + client + "-" + request).allowed());
                }
            }
            assertTrue(limiter.preAuthTokenWindowCount() < 100);
            clock.advanceSeconds(60);
        }

        assertTrue(limiter.acquireBeforeAuthentication(
            "198.51.100.7", "legitimate").allowed());
        assertTrue(limiter.preAuthTokenWindowCount() < 100);
    }

    @Test
    void idleLegitimateClientIsStillServedAfterItsWindowExpiresUnderSaturation() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 1_000, 600, 60, clock, HMAC_KEY, 100, 20, 3);

        assertTrue(limiter.acquireBeforeAuthentication(
            "198.51.100.7", "legitimate").allowed());
        for (int client = 0; client < 4; client++) {
            for (int request = 0; request < 100; request++) {
                assertTrue(limiter.acquireBeforeAuthentication(
                    "192.0.2." + client, "flood-" + client + "-" + request).allowed());
            }
        }
        assertTrue(limiter.preAuthTokenWindowCount() < 20);

        clock.advanceSeconds(60);

        ApiRateLimiter.Decision decision = limiter.acquireBeforeAuthentication(
            "198.51.100.7", "legitimate");
        assertTrue(decision.allowed());
        assertEquals(600, decision.limit());
    }

    @Test
    void overQuotaClientCannotAllocateFurtherTokenWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 100, 60, clock, HMAC_KEY, 100, 100, 4);

        for (int request = 0; request < 50; request++) {
            assertTrue(limiter.acquireBeforeAuthentication(
                "192.0.2.1", "token-" + request).allowed());
        }

        assertEquals(4, limiter.preAuthTokenWindowCount());
    }

    @Test
    void perClientQuotaDoesNotWeakenTheCrossClientTokenBucket() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 2, 60, clock, HMAC_KEY, 100, 100, 2);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "shared").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "quota-fill").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "shared").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.2", "shared").allowed());
    }

    @Test
    void namespaceSaturationCannotResetAnExhaustedTokenBucket() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 2, 60, clock, HMAC_KEY, 100, 3, 60);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "target").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.2", "target").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.3", "target").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.4", "filler-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.5", "filler-b").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.6", "filler-c").allowed());

        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.7", "target").allowed());
        assertEquals(3, limiter.preAuthTokenWindowCount());
    }

    @Test
    void fallbackAdmissionStillConsumesTheClientAllowance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 3, 100, 60, clock, HMAC_KEY, 100, 100, 2);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-a").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-b").allowed());
        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-c").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.1", "token-d").allowed());
        assertEquals(2, limiter.preAuthTokenWindowCount());
    }

    @Test
    void opportunisticEvictionRunsAtMostOncePerSecondPerNamespace() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 100, 100, 60, clock, HMAC_KEY, 1, 1, 60);

        assertTrue(limiter.acquireBeforeAuthentication("192.0.2.1", "token-a").allowed());
        for (int request = 0; request < 20; request++) {
            assertFalse(limiter.acquireBeforeAuthentication(
                "192.0.2." + (request + 2), "blocked-client-" + request).allowed());
            assertFalse(limiter.acquireBeforeAuthentication(
                "192.0.2.1", "blocked-token-" + request).allowed());
        }
        assertEquals(1, limiter.clientEvictionScanCount());
        assertEquals(1, limiter.tokenEvictionScanCount());

        clock.advanceSeconds(1);
        assertFalse(limiter.acquireBeforeAuthentication("198.51.100.1", "blocked").allowed());
        assertFalse(limiter.acquireBeforeAuthentication("192.0.2.1", "still-blocked").allowed());
        assertEquals(2, limiter.clientEvictionScanCount());
        assertEquals(2, limiter.tokenEvictionScanCount());
    }

    @Test
    void concurrentPreAuthenticationAdmissionsRespectTheClientLimit() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ApiRateLimiter limiter = new ApiRateLimiter(
            100, 500, 100, 60, clock, HMAC_KEY, 100, 100, 60);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < 8; thread++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int request = 0; request < 200; request++) {
                        if (limiter.acquireBeforeAuthentication("192.0.2.1", null).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(500, allowed.get());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
