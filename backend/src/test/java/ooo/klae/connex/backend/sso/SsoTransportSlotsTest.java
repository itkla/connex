package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SsoTransportSlotsTest {
    @Test
    void parkedWaiterKeepsItsEntryAcrossBothReleasesAndCannotOverReleaseANewEntry() throws Exception {
        Semaphore permits = spy(new Semaphore(2, true));
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 3) {
                parked.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(permits).tryAcquire(anyLong(), eq(TimeUnit.NANOSECONDS));
        SsoTransportSlots slots = new SsoTransportSlots(() -> permits);
        Map<?, ?> entries = assertInstanceOf(Map.class, ReflectionTestUtils.getField(slots, "slots"));
        var first = slots.acquire("idp.example", 0);
        var second = slots.acquire("idp.example", 0);
        Object original = entries.get("idp.example");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var waiter = executor.submit(() -> slots.acquire("idp.example", TimeUnit.SECONDS.toNanos(10)));
            try {
                assertTrue(parked.await(10, TimeUnit.SECONDS));
                first.close();
                second.close();
                assertEquals(2, permits.availablePermits());
                assertSame(original, entries.get("idp.example"));
                try (var newcomer = slots.acquire("idp.example", 0)) {
                    resume.countDown();
                    try (var held = waiter.get(10, TimeUnit.SECONDS)) {
                        assertSame(original, entries.get("idp.example"));
                        assertEquals(0, permits.availablePermits());
                        assertThrows(SsoTransportSaturatedException.class, () -> slots.acquire("idp.example", 0));
                    }
                    assertEquals(1, permits.availablePermits());
                }
                assertEquals(2, permits.availablePermits());
                assertTrue(entries.isEmpty());
                first.close();
                second.close();
                assertEquals(2, permits.availablePermits());
            } finally {
                resume.countDown();
                first.close();
                second.close();
            }
        }
    }

    @Test
    void rejectedWaiterDropsOnlyItsOwnReference() throws Exception {
        SsoTransportSlots slots = new SsoTransportSlots(1);
        Map<?, ?> entries = assertInstanceOf(Map.class, ReflectionTestUtils.getField(slots, "slots"));
        try (var held = slots.acquire("idp.example", 0)) {
            Object original = entries.get("idp.example");
            assertThrows(SsoTransportSaturatedException.class, () -> slots.acquire("idp.example", 0));
            assertSame(original, entries.get("idp.example"));
        }
        assertTrue(entries.isEmpty());
        try (var next = slots.acquire("idp.example", 0)) {
            assertEquals(1, entries.size());
        }
        assertTrue(entries.isEmpty());
    }
}
