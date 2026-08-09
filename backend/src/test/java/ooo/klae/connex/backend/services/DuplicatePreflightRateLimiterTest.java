package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

@ExtendWith(MockitoExtension.class)
class DuplicatePreflightRateLimiterTest {

    @Mock private WorkspaceService workspaceService;

    private DuplicatePreflightProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DuplicatePreflightProperties();
        properties.setMaxRequestsPerMinute(2);
        properties.setMaxGlobalRequestsPerMinute(4);
        properties.setMaxRateLimitKeys(2);
        properties.setMaxWorkflowCredits(2);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        org.mockito.Mockito.lenient()
            .when(workspaceService.getCurrentUserId()).thenReturn(9);
    }

    @Test
    void scopesPrincipalBudgetsByWorkspace() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(6);
        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed(1));
    }

    @Test
    void preservesAGlobalBudgetAcrossPrincipals() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");

        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        when(workspaceService.getCurrentUserId()).thenReturn(10);
        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(6);
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed(1));
    }

    @Test
    void unchangedCommitConsumesThePreviewsSingleUseCredit() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");
        String fingerprint = "a".repeat(64);
        String resultFingerprint = "b".repeat(64);
        String context = "d".repeat(64);

        String proof = limiter.requirePreviewAllowed(2, fingerprint, context);
        limiter.recordPreviewResult(fingerprint, proof, resultFingerprint);
        DuplicatePreflightRateLimiter.CommitAdmission admission =
            limiter.claimCommitAllowed(proof, context);
        assertEquals(
            resultFingerprint,
            limiter.requireCommitAllowed(fingerprint, admission));
        assertNull(limiter.claimCommitAllowed(proof, context));
        assertNull(limiter.requireCommitAllowed(fingerprint, admission));
        assertThrows(TooManyRequestsException.class, () -> limiter.requireAllowed(1));
    }

    @Test
    void claimingOneParallelPreviewInvalidatesSiblingProofs() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");
        String fingerprint = "a".repeat(64);
        String context = "d".repeat(64);

        String firstProof = limiter.requirePreviewAllowed(2, fingerprint, context);
        limiter.recordPreviewResult(fingerprint, firstProof, "b".repeat(64));
        String refreshedProof = limiter.requirePreviewAllowed(2, fingerprint, context);
        limiter.recordPreviewResult(
            fingerprint, refreshedProof, "c".repeat(64));
        assertThrows(
            TooManyRequestsException.class,
            () -> limiter.requirePreviewAllowed(1, fingerprint, context));
        assertEquals(
            "c".repeat(64),
            limiter.requireCommitAllowed(
                fingerprint,
                limiter.claimCommitAllowed(refreshedProof, context)));
        assertNull(limiter.claimCommitAllowed(firstProof, context));
    }

    @Test
    void activeCommitClaimBlocksRefreshUntilTransactionCompletion() {
        MutableClock clock = new MutableClock("2026-07-26T12:00:10Z");
        DuplicatePreflightRateLimiter limiter = new DuplicatePreflightRateLimiter(
            properties, workspaceService, clock);
        String fingerprint = "a".repeat(64);
        String context = "d".repeat(64);

        String proof = limiter.requirePreviewAllowed(1, fingerprint, context);
        limiter.recordPreviewResult(fingerprint, proof, "b".repeat(64));
        DuplicatePreflightRateLimiter.CommitAdmission admission =
            limiter.claimCommitAllowed(proof, context);
        clock.advanceSeconds(properties.getReviewProofTtl().toSeconds() + 1);

        assertThrows(
            ConflictException.class,
            () -> limiter.requirePreviewAllowed(1, fingerprint, context));
        limiter.releaseCommitAdmission(admission);
        assertDoesNotThrow(
            () -> limiter.requirePreviewAllowed(1, fingerprint, context));
    }

    @Test
    void recordsAReviewResultAfterTheRateLimitMinuteChanges() {
        MutableClock clock = new MutableClock("2026-07-26T12:00:59Z");
        DuplicatePreflightRateLimiter limiter = new DuplicatePreflightRateLimiter(
            properties, workspaceService, clock);
        String fingerprint = "a".repeat(64);
        String resultFingerprint = "b".repeat(64);
        String context = "d".repeat(64);

        String proof = limiter.requirePreviewAllowed(2, fingerprint, context);
        clock.advanceSeconds(2);
        limiter.recordPreviewResult(fingerprint, proof, resultFingerprint);

        assertEquals(
            resultFingerprint,
            limiter.requireCommitAllowed(
                fingerprint,
                limiter.claimCommitAllowed(proof, context)));
    }

    @Test
    void changedOrRepeatedCommitRequiresNewAdmission() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");
        String context = "d".repeat(64);

        String proof = limiter.requirePreviewAllowed(
            1, "a".repeat(64), context);
        limiter.recordPreviewResult(
            "a".repeat(64), proof, "c".repeat(64));

        assertNull(limiter.claimCommitAllowed(proof, "e".repeat(64)));
        DuplicatePreflightRateLimiter.CommitAdmission admission =
            limiter.claimCommitAllowed(proof, context);
        assertNull(limiter.requireCommitAllowed("b".repeat(64), admission));
        assertNull(limiter.requireCommitAllowed("a".repeat(64), admission));
        assertNull(limiter.claimCommitAllowed(proof, context));
    }

    @Test
    void failedPreviewReleasesItsReservedProof() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");
        String fingerprint = "a".repeat(64);
        String context = "d".repeat(64);

        String proof = limiter.requirePreviewAllowed(1, fingerprint, context);
        limiter.cancelPreview(fingerprint, proof);
        limiter.recordPreviewResult(fingerprint, proof, "b".repeat(64));

        assertNull(limiter.claimCommitAllowed(proof, context));
    }

    @Test
    void rejectsInvalidWorkAndFingerprintInputs() {
        DuplicatePreflightRateLimiter limiter = limiterAt("2026-07-26T12:00:10Z");

        assertThrows(IllegalArgumentException.class, () -> limiter.requireAllowed(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> limiter.requirePreviewAllowed(
                1, "not-a-digest", "d".repeat(64)));
        assertThrows(
            IllegalArgumentException.class,
            () -> limiter.requirePreviewAllowed(
                1, "A".repeat(64), "d".repeat(64)));
        String proof = limiter.requirePreviewAllowed(
            1, "a".repeat(64), "d".repeat(64));
        assertThrows(
            IllegalArgumentException.class,
            () -> limiter.recordPreviewResult(
                "a".repeat(64), proof, "not-a-digest"));
    }

    @Test
    void resetsAtTheNextMinute() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        when(clock.instant())
            .thenReturn(Instant.parse("2026-07-26T12:00:10Z"))
            .thenReturn(Instant.parse("2026-07-26T12:01:00Z"));
        DuplicatePreflightRateLimiter limiter = new DuplicatePreflightRateLimiter(
            properties, workspaceService, clock);

        assertDoesNotThrow(() -> limiter.requireAllowed(2));
        assertDoesNotThrow(() -> limiter.requireAllowed(2));
    }

    @Test
    void rejectsAnUnavailablePrincipal() {
        when(workspaceService.getCurrentUserId()).thenReturn(0);

        assertThrows(
            IllegalStateException.class,
            () -> limiterAt("2026-07-26T12:00:10Z").requireAllowed(1));
    }

    private DuplicatePreflightRateLimiter limiterAt(String instant) {
        return new DuplicatePreflightRateLimiter(
            properties,
            workspaceService,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(String instant) {
            this.instant = Instant.parse(instant);
        }

        private void advanceSeconds(long seconds) {
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
