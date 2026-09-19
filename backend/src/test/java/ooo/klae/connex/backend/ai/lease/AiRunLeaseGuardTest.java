package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Unit coverage of the owner-side ownership flag and its monotonic self-fence. */
class AiRunLeaseGuardTest {

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void aFreshGuardLetsTheOwnerWork() {
        AiRunLeaseGuard guard = guard();

        assertFalse(guard.isStopped());
        assertEquals(Optional.empty(), guard.reason());
    }

    @Test
    void theFirstAuthoritativeStopReasonWins() {
        AiRunLeaseGuard guard = guard();

        guard.stop(AiRunLeaseGuard.LEASE_LOST);
        guard.stop(AiRunLeaseGuard.SUBJECT_STOPPED);

        assertTrue(guard.isStopped());
        assertEquals(Optional.of(AiRunLeaseGuard.LEASE_LOST), guard.reason());
    }

    @Test
    void theSelfFenceTripsOnceTheRenewalGapExceedsTheLeaseLifetime() {
        AiRunLeaseGuard guard = guard();

        nanos.set(Duration.ofSeconds(45).toNanos());
        assertFalse(guard.isStopped());

        nanos.set(Duration.ofSeconds(45).toNanos() + 1L);
        assertTrue(guard.isStopped());
        assertEquals(Optional.of(AiRunLeaseGuard.RENEW_GAP), guard.reason());
    }

    @Test
    void aSuccessfulRenewalRestartsTheSelfFenceWindow() {
        AiRunLeaseGuard guard = guard();

        nanos.set(Duration.ofSeconds(40).toNanos());
        guard.recordRenewal();
        nanos.set(Duration.ofSeconds(84).toNanos());

        assertFalse(guard.isStopped());

        nanos.set(Duration.ofSeconds(86).toNanos());
        assertTrue(guard.isStopped());
    }

    @Test
    void aNonPositiveLifetimeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new AiRunLeaseGuard(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AiRunLeaseGuard(Duration.ofSeconds(-1)));
    }

    private AiRunLeaseGuard guard() {
        return new AiRunLeaseGuard(Duration.ofSeconds(45), nanos::get);
    }
}
