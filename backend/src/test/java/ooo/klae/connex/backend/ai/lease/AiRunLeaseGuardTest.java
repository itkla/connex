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
        guard.recordRenewal(guard.clockNanos());
        nanos.set(Duration.ofSeconds(84).toNanos());

        assertFalse(guard.isStopped());

        nanos.set(Duration.ofSeconds(86).toNanos());
        assertTrue(guard.isStopped());
    }

    /**
     * The window runs from the instant the renewal was issued, because the database wrote its own
     * deadline part-way through that round trip. Anchoring on completion instead would put the
     * local deadline after the database's, leaving a window in which a settler may take the run
     * over while this guard still answers healthy.
     */
    @Test
    void theWindowRunsFromTheRenewalsIssueInstantRatherThanItsCompletion() {
        AiRunLeaseGuard guard = guard();
        long issuedAt = guard.clockNanos();

        nanos.set(Duration.ofSeconds(10).toNanos());
        guard.recordRenewal(issuedAt);

        nanos.set(Duration.ofSeconds(45).toNanos() + 1L);
        assertTrue(guard.isStopped());
        assertEquals(Optional.of(AiRunLeaseGuard.RENEW_GAP), guard.reason());
    }

    @Test
    void aLateRenewalRecordIsNeverAllowedToRewindTheWindow() {
        AiRunLeaseGuard guard = guard();

        nanos.set(Duration.ofSeconds(30).toNanos());
        guard.recordRenewal(guard.clockNanos());
        guard.recordRenewal(0L);

        nanos.set(Duration.ofSeconds(74).toNanos());
        assertFalse(guard.isStopped());
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
