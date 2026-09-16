package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

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
    void concurrentBurstAdmitsExactlyTheFirstCapAtomicUpdates() throws Exception {
        int cap = 20;
        int attempts = 40;
        AccountCreationRateLimiter limiter = new AccountCreationRateLimiter(cap, 900, new MutableClock());
        BurstWindows<Integer, Object> windows = new BurstWindows<>(attempts);
        ReflectionTestUtils.setField(limiter, "windows", windows);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<Admission>> requests = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "the burst must be released");
                    boolean admitted = limiter.tryAcquire(7);
                    return new Admission(windows.currentRequestOrder(), admitted);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "every request must be ready");
            start.countDown();
            List<Admission> admissions = new ArrayList<>();
            for (Future<Admission> request : requests) {
                admissions.add(request.get(15, TimeUnit.SECONDS));
            }

            assertEquals(cap, admissions.stream().filter(Admission::admitted).count());
            for (Admission admission : admissions) {
                assertEquals(admission.order() <= cap, admission.admitted(),
                        "admission must reflect this request's atomic update: " + admission.order());
            }
        } finally {
            start.countDown();
        }
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

    private record Admission(int order, boolean admitted) {}

    /**
     * Runs the real per-key atomic update, then holds its return until every contender has updated.
     * This forces admitted callers to observe later attempts if their returned window is mutable.
     */
    private static final class BurstWindows<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final AtomicInteger sequence = new AtomicInteger();
        private final ConcurrentHashMap<Thread, Integer> order = new ConcurrentHashMap<>();
        private final CountDownLatch updated;

        private BurstWindows(int attempts) {
            updated = new CountDownLatch(attempts);
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            V result = super.compute(key, (actor, existing) -> {
                order.put(Thread.currentThread(), sequence.incrementAndGet());
                return remappingFunction.apply(actor, existing);
            });
            updated.countDown();
            try {
                assertTrue(updated.await(10, TimeUnit.SECONDS), "all atomic updates must complete");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the burst", exception);
            }
            return result;
        }

        private int currentRequestOrder() {
            return Objects.requireNonNull(order.get(Thread.currentThread()));
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
