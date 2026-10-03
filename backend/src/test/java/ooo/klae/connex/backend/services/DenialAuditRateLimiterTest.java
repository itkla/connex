package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DenialAuditRateLimiterTest {
    private static final Instant FIXED = Instant.parse("2026-10-01T00:00:00Z");
    private static final long WINDOW_SECONDS = 3_600;
    private static final String CONFINED = "auth.mfa.policy.denied";
    private static final String STEP_UP = AuditService.EXPORT_STEP_UP_ACTION;
    private static final String VICTIM_ADDRESS = "203.0.113.5";
    private static final String OTHER_ADDRESS = "198.51.100.9";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void admitsOneRowPerUserActionAndAddressPerWindow() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * A suppressed row leaves no audit evidence, so a forged flood would otherwise be silent. Each
     * suppression, whether a repeat inside the window or an address over the cap, is counted against
     * its action; an admitted row is not.
     */
    @Test
    void suppressedRowsAreCountedPerAction() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        for (int host = 1; host <= DenialAuditRateLimiter.MAX_ADDRESSES_PER_DENIAL; host++) {
            assertTrue(admits(limiter, 7, STEP_UP, "2001:db8::" + host));
        }
        assertFalse(admits(limiter, 7, STEP_UP, "2001:db8::ff"));

        assertEquals(2.0, suppressed(CONFINED));
        assertEquals(1.0, suppressed(STEP_UP));
        assertEquals(0.0, evictions());
    }

    /**
     * Pins the client address into the key. A cross-site trigger always arrives from the victim's own
     * browser, so forged rows stay bounded; a stolen session used from another address must still
     * leave its own evidence, which is what export step-up exists to catch.
     */
    @Test
    void anotherAddressKeepsLeavingItsOwnEvidence() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
        assertTrue(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));
        assertFalse(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));
        assertFalse(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
    }

    @Test
    void usersAndActionsKeepTheirOwnWindows() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertTrue(admits(limiter, 8, CONFINED, VICTIM_ADDRESS));
        assertTrue(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 8, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
    }

    @Test
    void anUnresolvedAddressKeepsItsOwnWindow() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(admits(limiter, 7, CONFINED, null));
        assertFalse(admits(limiter, 7, CONFINED, null));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void theWindowReadmitsOnceItHasElapsed() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));

        clock.advance(Duration.ofSeconds(WINDOW_SECONDS).minusMillis(1));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));

        clock.advance(Duration.ofMillis(1));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * Elapsed time saturates instead of wrapping, so an admission from beyond the clock's range is
     * expired rather than live forever. Window expiry is decided by the latest admission alone, which
     * matches checking every admission only while elapsed time never decreases for an earlier start.
     */
    @Test
    void anAdmissionBeyondTheClockRangeExpiresInsteadOfStayingLive() {
        TestClock clock = new TestClock(0);
        clock.set(Long.MIN_VALUE);
        DenialAuditRateLimiter limiter = limiter(clock);
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));

        clock.set(Duration.ofSeconds(WINDOW_SECONDS).toMillis());

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * An audit row that could not be written must not suppress the next denial's row for the rest of
     * the window: losing evidence is the worse failure.
     */
    @Test
    void releasingAnUnwrittenRowReadmitsTheNextDenial() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        DenialAuditRateLimiter.Admission admission =
                limiter.acquire(7, CONFINED, VICTIM_ADDRESS).orElseThrow();

        limiter.release(admission);

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void releasingOneAddressLeavesTheOtherAddressesWindowsInPlace() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(1));
        DenialAuditRateLimiter.Admission victim = limiter.acquire(7, STEP_UP, VICTIM_ADDRESS).orElseThrow();
        assertTrue(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));

        limiter.release(victim);

        assertTrue(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));
    }

    /**
     * Concurrent denials for one key must admit exactly one row however the threads interleave; the
     * window is opened inside a single atomic map update rather than by a read followed by a write.
     */
    @Test
    void concurrentDenialsForOneKeyAreAdmittedOnce() throws Exception {
        DenialAuditRateLimiter limiter = limiter(Clock.systemUTC());
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return admits(limiter, 7, CONFINED, VICTIM_ADDRESS);
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }

            assertEquals(1, admitted);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A write that fails after its window has already been replaced must not drop the newer window,
     * or one late failure would re-open the bound for that address.
     */
    @Test
    void aLateReleaseDropsOnlyTheWindowItOpened() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        DenialAuditRateLimiter.Admission stale = limiter.acquire(7, CONFINED, VICTIM_ADDRESS).orElseThrow();
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));

        limiter.release(stale);

        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * Any signed-in user can trigger an export step-up denial, so rotating source addresses, for
     * example across an IPv6 /64, must not reopen the per-request volume the window bounds.
     */
    @Test
    void addressesAdmittedPerUserAndActionAreCapped() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        for (int host = 1; host <= DenialAuditRateLimiter.MAX_ADDRESSES_PER_DENIAL; host++) {
            assertTrue(admits(limiter, 7, STEP_UP, "2001:db8::" + host));
        }

        assertFalse(admits(limiter, 7, STEP_UP, "2001:db8::ff"));
        assertTrue(admits(limiter, 8, STEP_UP, "2001:db8::ff"));
        assertTrue(admits(limiter, 7, CONFINED, "2001:db8::ff"));

        clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        assertTrue(admits(limiter, 7, STEP_UP, "2001:db8::ff"));
    }

    /**
     * Proves the key set stays bounded rather than growing with users and addresses. Once the bound
     * is reached the oldest suppression is dropped, so the earliest key becomes admissible again inside
     * its own window: an extra audit row is a better failure than unbounded memory.
     *
     * <p>The clock advances per reading so "oldest" is a total order. Under a fixed clock every window
     * shares a timestamp and eviction would pick an arbitrary entry.
     */
    @Test
    void trackedWindowsAreBoundedByEvictingTheOldest() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(1));
        assertTrue(admits(limiter, 0, CONFINED, VICTIM_ADDRESS));
        for (int user = 1; user <= 4_096; user++) {
            admits(limiter, user, CONFINED, VICTIM_ADDRESS);
        }

        assertEquals(4_096, limiter.trackedDenials());
        assertEquals(1.0, evictions());
        assertTrue(admits(limiter, 0, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void expiredWindowsAreEvictedBeforeLiveOnes() {
        TestClock clock = new TestClock(1);
        DenialAuditRateLimiter limiter = limiter(clock);
        for (int user = 0; user < 10; user++) {
            assertTrue(admits(limiter, user, CONFINED, VICTIM_ADDRESS));
        }
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        for (int user = 10; user < 4_102; user++) {
            assertTrue(admits(limiter, user, CONFINED, VICTIM_ADDRESS));
        }

        assertEquals(4_092, limiter.trackedDenials());
        assertEquals(0.0, evictions());
        assertFalse(admits(limiter, 10, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * New keys racing past the limit can leave more than one key over it when an eviction runs. The
     * key set must still end within the bound, and every live window dropped must be counted exactly
     * once however the threads interleave: tracked plus evicted always equals admitted.
     */
    @Test
    void concurrentNewKeysStayBoundedAndEveryEvictionIsCounted() throws Exception {
        DenialAuditRateLimiter limiter = limiter(Clock.systemUTC());
        int threads = 8;
        int keysPerThread = 1_024;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int firstUser = thread * keysPerThread;
                results.add(pool.submit(() -> {
                    start.await();
                    for (int user = firstUser; user < firstUser + keysPerThread; user++) {
                        assertTrue(admits(limiter, user, CONFINED, VICTIM_ADDRESS));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }

            assertTrue(limiter.trackedDenials() <= DenialAuditRateLimiter.MAX_TRACKED_DENIALS);
            assertEquals(threads * keysPerThread, limiter.trackedDenials() + (long) evictions());
        } finally {
            pool.shutdownNow();
        }
    }

    private DenialAuditRateLimiter limiter(Clock clock) {
        return new DenialAuditRateLimiter(WINDOW_SECONDS, clock, meterRegistry);
    }

    private double suppressed(String action) {
        return meterRegistry.counter(DenialAuditRateLimiter.SUPPRESSED_METRIC, "action", action).count();
    }

    private double evictions() {
        return meterRegistry.counter(DenialAuditRateLimiter.EVICTED_METRIC).count();
    }

    private static boolean admits(DenialAuditRateLimiter limiter, int userId, String action, String address) {
        return limiter.acquire(userId, action, address).isPresent();
    }

    /** Settable clock that also steps by a fixed amount per reading, so every window can start apart. */
    private static final class TestClock extends Clock {
        private final AtomicLong currentMillis = new AtomicLong(FIXED.toEpochMilli());
        private final long stepMillis;

        private TestClock(long stepMillis) {
            this.stepMillis = stepMillis;
        }

        private void advance(Duration duration) {
            currentMillis.addAndGet(duration.toMillis());
        }

        private void set(long millis) {
            currentMillis.set(millis);
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
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            return currentMillis.getAndAdd(stepMillis);
        }
    }
}
