package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

class DenialAuditRateLimiterTest {
    private static final Instant FIXED = Instant.parse("2026-10-01T00:00:00Z");
    private static final long WINDOW_SECONDS = 3_600;
    private static final String CONFINED = "auth.mfa.policy.denied";
    private static final String STEP_UP = AuditService.EXPORT_STEP_UP_ACTION;
    private static final String VICTIM_ADDRESS = "203.0.113.5";
    private static final String OTHER_ADDRESS = "198.51.100.9";
    private static final String REASON = "enrollment_required";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void fingerprintsShareTheOriginalAddressWindowWithoutExtendingIt() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));

        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        clock.advance(Duration.ofMillis(1));

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
    }

    @Test
    void oneThousandRotatingUserAgentsAdmitFourAndCountEverySuppression() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        int admitted = 0;
        for (int agent = 0; agent < 1_000; agent++) {
            if (admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser " + agent)) {
                admitted++;
            }
        }

        assertEquals(4, admitted);
        assertEquals(996.0, suppressed(CONFINED));
        assertEquals(1, limiter.trackedDenials());
        assertEquals(0.0, evictions());
        assertTrue(admits(limiter, 7, CONFINED, OTHER_ADDRESS, "Browser 0"));
    }

    @Test
    void eightAddressesEachKeepFourFingerprintSlotsEvenWhenAllAddressesAreOccupied() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        for (int address = 0; address < 8; address++) {
            assertTrue(admits(limiter, 7, CONFINED, "2001:db8::" + address, "Browser 0"));
        }
        for (int address = 0; address < 8; address++) {
            for (int agent = 1; agent < 4; agent++) {
                assertTrue(admits(limiter, 7, CONFINED, "2001:db8::" + address, "Browser " + agent));
            }
            assertFalse(admits(limiter, 7, CONFINED, "2001:db8::" + address, "Browser 4"));
            assertFalse(admits(limiter, 7, CONFINED, "2001:db8::" + address, "Browser 0"));
        }
        assertFalse(admits(limiter, 7, CONFINED, "2001:db8::ff", "Browser 0"));
        assertEquals(17.0, suppressed(CONFINED));
        assertEquals(0.0, evictions());
    }

    @Test
    void hashesTheFullUtf8HeaderAndDistinguishesAcceptedEmptyHeadersFromAbsence() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        DenialAuditRateLimiter.Admission unicode = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("é🚀")).orElseThrow();
        assertEquals("5db4d7b036b83313f6bf71b894e20df431c6641a66b76a9a34f09025568468d2",
                unicode.userAgentFingerprint());
        String prefix = "Browser/" + "x".repeat(2_000);
        DenialAuditRateLimiter.Admission first = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request(prefix + "A")).orElseThrow();
        DenialAuditRateLimiter.Admission second = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request(prefix + "B")).orElseThrow();
        assertEquals(64, first.userAgentFingerprint().length());
        assertNotEquals(first.userAgentFingerprint(), second.userAgentFingerprint());

        DenialAuditRateLimiter.Admission absent = limiter.acquire(7, CONFINED, REASON, OTHER_ADDRESS,
                request(null)).orElseThrow();
        DenialAuditRateLimiter.Admission empty = limiter.acquire(7, CONFINED, REASON, OTHER_ADDRESS,
                request("")).orElseThrow();
        assertEquals("", absent.userAgentFingerprint());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                empty.userAgentFingerprint());
    }

    @Test
    void absentAndFirewallRejectedUserAgentsShareOneSentinelWithoutThrowing() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        for (String agent : List.of("Browser A\u0085", "Browser B\u0085")) {
            var firewalled = new StrictHttpFirewall().getFirewalledRequest(request(agent));
            assertThrows(RequestRejectedException.class, () -> firewalled.getHeader("User-Agent"));
            assertTrue(limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS, firewalled).isEmpty());
        }
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertEquals(3.0, suppressed(CONFINED));
    }

    /** Replays the old address-only rule independently across dense traffic and staggered expiry. */
    @Test
    void everyAddressOnlyAdmissionIsAlsoAdmittedWithoutReleasesForASeededRequestSequence() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        AddressOnlyLimiter reference = new AddressOnlyLimiter();
        Random random = new Random(1936);
        int oldAdmissions = 0;
        int additionalAdmissions = 0;
        for (int attempt = 0; attempt < 20_000; attempt++) {
            clock.advance(Duration.ofMillis(random.nextInt(5_000)));
            if (attempt % 257 == 0) {
                clock.advance(Duration.ofMinutes(10));
            }
            int userId = random.nextInt(4);
            String action = random.nextBoolean() ? CONFINED : STEP_UP;
            String reason = random.nextBoolean() ? REASON : "attestation_required";
            String address = random.nextInt(12) == 0 ? null : "2001:db8::" + random.nextInt(12);
            String agent = random.nextInt(8) == 0 ? null : "Browser " + random.nextInt(16);
            boolean oldAdmission = reference.admits(userId, action, reason, address, clock.millis());
            boolean newAdmission = limiter.acquire(userId, action, reason, address, request(agent)).isPresent();
            assertTrue(!oldAdmission || newAdmission, "Lost address-only admission at request " + attempt);
            if (oldAdmission) {
                oldAdmissions++;
            } else if (newAdmission) {
                additionalAdmissions++;
            }
        }

        assertTrue(oldAdmissions > 100);
        assertTrue(additionalAdmissions > 100);
        assertTrue(suppressed(CONFINED) > 0);
        assertEquals(0.0, evictions());
    }

    @Test
    void seededOpeningAdmissionFailuresPreserveEvidenceForRecordedFingerprintRetries() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        AddressOnlyLimiter reference = new AddressOnlyLimiter();
        Random random = new Random(1936);
        int preservedRetries = 0;
        for (int attempt = 0; attempt < 1_000; attempt++) {
            String action = random.nextBoolean() ? CONFINED : STEP_UP;
            String reason = random.nextBoolean() ? REASON : "attestation_required";
            String address = "2001:db8::" + random.nextInt(12);
            String firstAgent = "Opening browser " + random.nextInt(16);
            String recordedAgent = "Recorded browser " + random.nextInt(16);
            Set<String> recordedAgents = new HashSet<>();
            assertTrue(reference.admits(attempt, action, reason, address, clock.millis()));
            DenialAuditRateLimiter.Admission opening = limiter.acquire(attempt, action, reason,
                    address, request(firstAgent)).orElseThrow();
            recordedAgents.add(firstAgent);
            clock.advance(Duration.ofMillis(random.nextInt(1_000) + 1));
            assertFalse(reference.admits(attempt, action, reason, address, clock.millis()));
            assertTrue(limiter.acquire(attempt, action, reason, address, request(recordedAgent)).isPresent());
            recordedAgents.add(recordedAgent);

            reference.release(attempt, action, reason, address, opening.admittedAtMillis());
            limiter.release(opening);
            recordedAgents.remove(firstAgent);
            boolean oldAdmission = reference.admits(attempt, action, reason, address, clock.millis());
            boolean newAdmission = limiter.acquire(attempt, action, reason, address,
                    request(recordedAgent)).isPresent();

            assertTrue(oldAdmission);
            assertFalse(newAdmission);
            assertTrue(newAdmission || recordedAgents.contains(recordedAgent),
                    "Lost recorded fingerprint evidence at retry " + attempt);
            preservedRetries++;
            assertFalse(reference.admits(attempt, action, reason, address, clock.millis()));
            assertTrue(limiter.acquire(attempt, action, reason, address, request(firstAgent)).isPresent());
            clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        }

        assertEquals(1_000, preservedRetries);
        assertEquals(0.0, evictions());
    }

    /** Retaining surviving rows also retains their address capacity until release or expiry. */
    @Test
    void retainingAReleasedOpeningSlotCanDenyANewAddressThatTheOldRuleWouldAdmit() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        AddressOnlyLimiter reference = new AddressOnlyLimiter();
        assertTrue(reference.admits(7, CONFINED, REASON, VICTIM_ADDRESS, clock.millis()));
        DenialAuditRateLimiter.Admission opening = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser A")).orElseThrow();
        for (int address = 1; address < 8; address++) {
            String peer = "2001:db8::" + address;
            assertTrue(reference.admits(7, CONFINED, REASON, peer, clock.millis()));
            assertTrue(admits(limiter, 7, CONFINED, peer, "Browser A"));
        }
        assertFalse(reference.admits(7, CONFINED, REASON, VICTIM_ADDRESS, clock.millis()));
        DenialAuditRateLimiter.Admission surviving = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser B")).orElseThrow();

        reference.release(7, CONFINED, REASON, VICTIM_ADDRESS, opening.admittedAtMillis());
        limiter.release(opening);

        assertTrue(reference.admits(7, CONFINED, REASON, OTHER_ADDRESS, clock.millis()));
        assertFalse(admits(limiter, 7, CONFINED, OTHER_ADDRESS, "Browser A"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        limiter.release(surviving);
        assertTrue(admits(limiter, 7, CONFINED, OTHER_ADDRESS, "Browser A"));
    }

    @Test
    void admitsOneRowPerUserActionAndAddressPerWindow() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    /**
     * A new denial reason for the same action is admitted at once rather than suppressed until the
     * previous reason's window ends (#1534): an account awaiting enrollment that enrolls an
     * unapproved passkey records that it now awaits approval.
     */
    @Test
    void aNewReasonForTheSameActionIsAdmittedWithinTheWindow() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(0));

        assertTrue(limiter.acquire(7, CONFINED, "enrollment_required", VICTIM_ADDRESS, request(null)).isPresent());
        assertTrue(limiter.acquire(7, CONFINED, "attestation_required", VICTIM_ADDRESS, request(null)).isPresent());
        assertFalse(limiter.acquire(7, CONFINED, "attestation_required", VICTIM_ADDRESS, request(null)).isPresent());
        assertFalse(limiter.acquire(7, CONFINED, "enrollment_required", VICTIM_ADDRESS, request(null)).isPresent());
        assertEquals(2.0, suppressed(CONFINED));
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
                limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS, request(null)).orElseThrow();

        limiter.release(admission);

        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void releasingOneAddressLeavesTheOtherAddressesWindowsInPlace() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(1));
        DenialAuditRateLimiter.Admission victim = limiter.acquire(7, STEP_UP, REASON, VICTIM_ADDRESS, request(null)).orElseThrow();
        assertTrue(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));

        limiter.release(victim);

        assertTrue(admits(limiter, 7, STEP_UP, VICTIM_ADDRESS));
        assertFalse(admits(limiter, 7, STEP_UP, OTHER_ADDRESS));
    }

    @Test
    void sameTimeFingerprintsReleaseIndependentlyAndTheFirstCanRetry() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        DenialAuditRateLimiter.Admission first = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser A")).orElseThrow();
        DenialAuditRateLimiter.Admission second = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser B")).orElseThrow();
        assertEquals(first.admittedAtMillis(), second.admittedAtMillis());

        limiter.release(second);

        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        limiter.release(first);
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));

        clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
    }

    @Test
    void releasingTheFirstFingerprintPreservesLaterRowsAndTheOriginalExpiry() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        DenialAuditRateLimiter.Admission first = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser A")).orElseThrow();
        clock.advance(Duration.ofMinutes(30));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));

        limiter.release(first);

        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        limiter.release(first);
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
        clock.advance(Duration.ofMillis(1));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
    }

    @Test
    void releasingTheOnlyFingerprintRestoresAddressCapacityAndStartsARetryWindow() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        DenialAuditRateLimiter.Admission first = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser A")).orElseThrow();
        for (int address = 1; address < 8; address++) {
            assertTrue(admits(limiter, 7, CONFINED, "2001:db8::" + address));
        }
        limiter.release(first);
        assertTrue(admits(limiter, 7, CONFINED, OTHER_ADDRESS));

        DenialAuditRateLimiter.Admission failed = limiter.acquire(8, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser A")).orElseThrow();
        clock.advance(Duration.ofMinutes(30));
        limiter.release(failed);
        assertTrue(admits(limiter, 8, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30));
        assertFalse(admits(limiter, 8, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30));
        assertTrue(admits(limiter, 8, CONFINED, VICTIM_ADDRESS, "Browser A"));
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
                    return admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A");
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
            assertEquals(15.0, suppressed(CONFINED));
            assertEquals(0.0, evictions());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDistinctFingerprintsAdmitExactlyFour() throws Exception {
        DenialAuditRateLimiter limiter = limiter(Clock.systemUTC());
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                String agent = "Browser " + thread;
                results.add(pool.submit(() -> {
                    start.await();
                    return admits(limiter, 7, CONFINED, VICTIM_ADDRESS, agent);
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }

            assertEquals(4, admitted);
            assertEquals(12.0, suppressed(CONFINED));
            assertEquals(0.0, evictions());
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
        DenialAuditRateLimiter.Admission stale = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS, request(null)).orElseThrow();
        clock.advance(Duration.ofSeconds(WINDOW_SECONDS));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));

        limiter.release(stale);

        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void aLateFingerprintReleasePreservesTheNextAddressWindow() {
        TestClock clock = new TestClock(0);
        DenialAuditRateLimiter limiter = limiter(clock);
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30));
        DenialAuditRateLimiter.Admission stale = limiter.acquire(7, CONFINED, REASON, VICTIM_ADDRESS,
                request("Browser B")).orElseThrow();
        clock.advance(Duration.ofMinutes(30));
        assertTrue(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));

        limiter.release(stale);

        assertFalse(admits(limiter, 7, CONFINED, VICTIM_ADDRESS, "Browser B"));
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
        clock.advance(Duration.ofMinutes(30));
        assertTrue(admits(limiter, 9, CONFINED, VICTIM_ADDRESS, "Browser A"));
        clock.advance(Duration.ofMinutes(30));
        for (int user = 10; user < 4_102; user++) {
            assertTrue(admits(limiter, user, CONFINED, VICTIM_ADDRESS));
        }

        assertEquals(4_092, limiter.trackedDenials());
        assertEquals(0.0, evictions());
        assertFalse(admits(limiter, 10, CONFINED, VICTIM_ADDRESS));
    }

    @Test
    void fingerprintSubAdmissionsNeverRefreshAddressEvictionOrder() {
        DenialAuditRateLimiter limiter = limiter(new TestClock(1));
        assertTrue(admits(limiter, 0, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 1, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 0, CONFINED, VICTIM_ADDRESS, "Browser B"));
        for (int user = 2; user <= DenialAuditRateLimiter.MAX_TRACKED_DENIALS; user++) {
            assertTrue(admits(limiter, user, CONFINED, VICTIM_ADDRESS, "Browser A"));
        }

        assertEquals(1.0, evictions());
        assertFalse(admits(limiter, 1, CONFINED, VICTIM_ADDRESS, "Browser A"));
        assertTrue(admits(limiter, 0, CONFINED, VICTIM_ADDRESS, "Browser A"));
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
        return admits(limiter, userId, action, address, null);
    }

    private static boolean admits(DenialAuditRateLimiter limiter, int userId, String action, String address,
            String userAgent) {
        return limiter.acquire(userId, action, REASON, address, request(userAgent)).isPresent();
    }

    private static MockHttpServletRequest request(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/companies");
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    private record ReferenceKey(int userId, String action, String reason) {
    }

    private record AddressOnlySlot(String address, long admittedAtMillis) {
    }

    private static final class AddressOnlyLimiter {
        private final Map<ReferenceKey, List<AddressOnlySlot>> windows = new HashMap<>();

        private boolean admits(int userId, String action, String reason, String address, long now) {
            ReferenceKey key = new ReferenceKey(userId, action, reason);
            List<AddressOnlySlot> live = new ArrayList<>(windows.getOrDefault(key, List.of()));
            live.removeIf(slot -> now - slot.admittedAtMillis() >= 3_600_000L);
            String resolvedAddress = address == null ? "" : address;
            boolean admitted = live.size() < 8
                    && live.stream().noneMatch(slot -> slot.address().equals(resolvedAddress));
            if (admitted) {
                live.add(new AddressOnlySlot(resolvedAddress, now));
            }
            windows.put(key, live);
            return admitted;
        }

        private void release(int userId, String action, String reason, String address, long admittedAtMillis) {
            ReferenceKey key = new ReferenceKey(userId, action, reason);
            List<AddressOnlySlot> live = windows.getOrDefault(key, List.of()).stream()
                    .filter(slot -> !slot.address().equals(address == null ? "" : address)
                            || slot.admittedAtMillis() != admittedAtMillis)
                    .toList();
            if (live.isEmpty()) {
                windows.remove(key);
            } else {
                windows.put(key, live);
            }
        }
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
