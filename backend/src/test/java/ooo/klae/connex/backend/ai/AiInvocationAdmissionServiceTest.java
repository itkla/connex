package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Admission;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.CacheIdentity;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Decision;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.LeaderOutcome;
import ooo.klae.connex.backend.ai.AiInvocationAdmissionService.Rejection;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class AiInvocationAdmissionServiceTest {
    @Mock private WorkspaceService workspaceService;

    private AiProperties properties;
    private AtomicInteger currentOrg;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.setInvocationQuotaAttemptsPerOrg(300);
        properties.setInvocationQuotaWindow(Duration.ofMinutes(10));
        properties.setInvocationRefreshThrottle(Duration.ofSeconds(30));
        properties.setInvocationQuotaMaxOrganizations(10000);
        properties.setInvocationRefreshMaxIdentities(10000);
        properties.setInvocationMaxActiveFlights(10000);
        currentOrg = new AtomicInteger(11);
        lenient().when(workspaceService.getCurrentOrgId()).thenAnswer(ignored -> currentOrg.get());
        clock = new MutableClock(Instant.parse("2026-07-31T12:00:00Z"));
    }

    @Test
    void oneIdentityHasExactlyOneConcurrentLeader() throws Exception {
        AiInvocationAdmissionService service = service();
        CacheIdentity identity = identity(7, 29);
        int callers = 12;
        CountDownLatch acquired = new CountDownLatch(callers);
        CountDownLatch releaseLeader = new CountDownLatch(1);
        AtomicInteger leaders = new AtomicInteger();
        AtomicInteger activeLeaders = new AtomicInteger();
        AtomicInteger maximumActiveLeaders = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        List<Future<Decision>> decisions = new ArrayList<>();

        try {
            for (int index = 0; index < callers; index++) {
                decisions.add(executor.submit(() -> {
                    try (Admission admission = service.acquire(identity, "hash", false)) {
                        if (admission.decision() == Decision.LEADER) {
                            leaders.incrementAndGet();
                            int active = activeLeaders.incrementAndGet();
                            maximumActiveLeaders.accumulateAndGet(active, Math::max);
                            acquired.countDown();
                            releaseLeader.await(5, TimeUnit.SECONDS);
                            activeLeaders.decrementAndGet();
                            admission.commitLeaderInvocation();
                            admission.completeLeader(LeaderOutcome.CACHE_READY);
                        } else {
                            acquired.countDown();
                            admission.awaitLeader();
                        }
                        return admission.decision();
                    }
                }));
            }

            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertEquals(1, leaders.get());
            assertEquals(1, service.activeFlightCount());
            releaseLeader.countDown();
            long leaderDecisions = 0;
            for (Future<Decision> decision : decisions) {
                if (decision.get(5, TimeUnit.SECONDS) == Decision.LEADER) {
                    leaderDecisions++;
                }
            }
            assertEquals(1, leaderDecisions);
            assertEquals(1, maximumActiveLeaders.get());
            assertEquals(0, service.activeFlightCount());
        } finally {
            releaseLeader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void followerConsumesNoOrganizationQuota() {
        properties.setInvocationQuotaAttemptsPerOrg(2);
        AiInvocationAdmissionService service = service();
        CacheIdentity first = identity(7, 29);
        CacheIdentity second = identity(7, 30);
        CacheIdentity third = identity(7, 31);

        try (Admission leader = service.acquire(first, "hash", false);
                Admission follower = service.acquire(first, "hash", false)) {
            assertEquals(Decision.LEADER, leader.decision());
            assertEquals(Decision.FOLLOWER, follower.decision());
            leader.commitLeaderInvocation();
            leader.completeLeader(LeaderOutcome.CACHE_READY);
            assertEquals(LeaderOutcome.CACHE_READY, follower.awaitLeader());
        }
        try (Admission leader = service.acquire(second, "hash", false)) {
            assertEquals(Decision.LEADER, leader.decision());
            leader.commitLeaderInvocation();
            leader.completeLeader(LeaderOutcome.FAILED);
        }
        try (Admission rejected = service.acquire(third, "hash", false)) {
            assertEquals(Decision.RATE_LIMITED, rejected.decision());
            assertEquals(Rejection.ORGANIZATION_QUOTA, rejected.rejection());
        }
    }

    @Test
    void organizationQuotaExhaustionIsRateLimited() {
        properties.setInvocationQuotaAttemptsPerOrg(1);
        AiInvocationAdmissionService service = service();

        try (Admission admitted = service.acquire(identity(7, 29), "hash", false)) {
            admitted.commitLeaderInvocation();
            admitted.completeLeader(LeaderOutcome.FAILED);
        }
        try (Admission rejected = service.acquire(identity(7, 30), "hash", false)) {
            assertEquals(Decision.RATE_LIMITED, rejected.decision());
            assertEquals(Rejection.ORGANIZATION_QUOTA, rejected.rejection());
        }
    }

    @Test
    void secondForcedRefreshWithinThrottleIsRejected() {
        AiInvocationAdmissionService service = service();
        CacheIdentity identity = identity(7, 29);

        try (Admission admitted = service.acquire(identity, "hash", true)) {
            assertEquals(Decision.LEADER, admitted.decision());
            admitted.commitLeaderInvocation();
            admitted.completeLeader(LeaderOutcome.FAILED);
        }
        clock.advance(Duration.ofSeconds(29));
        try (Admission rejected = service.acquire(identity, "hash", true)) {
            assertEquals(Decision.RATE_LIMITED, rejected.decision());
            assertEquals(Rejection.REFRESH_THROTTLE, rejected.rejection());
        }
    }

    @Test
    void forcedRefreshJoinsAnExistingFlightBeforeThrottleEvaluation() {
        AiInvocationAdmissionService service = service();
        CacheIdentity identity = identity(7, 29);

        try (Admission leader = service.acquire(identity, "hash", true);
                Admission follower = service.acquire(identity, "hash", true)) {
            assertEquals(Decision.LEADER, leader.decision());
            assertEquals(Decision.FOLLOWER, follower.decision());
            assertEquals(Rejection.NONE, follower.rejection());
            leader.commitLeaderInvocation();
            leader.completeLeader(LeaderOutcome.CACHE_READY);
            assertEquals(LeaderOutcome.CACHE_READY, follower.awaitLeader());
        }
    }

    @Test
    void differentContentHashesRunIndependentFlights() {
        AiInvocationAdmissionService service = service();
        CacheIdentity identity = identity(7, 29);

        try (Admission first = service.acquire(identity, "hash-a", false);
                Admission second = service.acquire(identity, "hash-b", false)) {
            assertEquals(Decision.LEADER, first.decision());
            assertEquals(Decision.LEADER, second.decision());
            assertEquals(2, service.activeFlightCount());
        }
    }

    @Test
    void followerTimeoutFailsAndDeregistersLeaderFlight() {
        AiInvocationAdmissionService service = new AiInvocationAdmissionService(
                properties, workspaceService, clock, Duration.ofMillis(25));
        CacheIdentity identity = identity(7, 29);

        try (Admission leader = service.acquire(identity, "hash", false);
                Admission follower = service.acquire(identity, "hash", false)) {
            assertEquals(LeaderOutcome.FAILED, assertTimeoutPreemptively(
                    Duration.ofSeconds(1), follower::awaitLeader));
            assertEquals(0, service.activeFlightCount());
            try (Admission retry = service.acquire(identity, "hash", false)) {
                assertEquals(Decision.LEADER, retry.decision());
            }
        }
    }

    @Test
    void fullLiveQuotaStateRejectsAnUnseenOrganization() {
        properties.setInvocationQuotaMaxOrganizations(1);
        AiInvocationAdmissionService service = service();

        try (Admission admitted = service.acquire(identity(7, 29), "hash", false)) {
            admitted.commitLeaderInvocation();
            admitted.completeLeader(LeaderOutcome.CACHE_READY);
        }
        currentOrg.set(12);
        try (Admission rejected = service.acquire(identity(8, 30), "hash", false)) {
            assertEquals(Decision.RATE_LIMITED, rejected.decision());
            assertEquals(Rejection.CAPACITY, rejected.rejection());
        }
        assertEquals(1, service.quotaStateSize());
    }

    @Test
    void staleQuotaAndRefreshEntriesArePurgedBeforeCapacityChecks() {
        properties.setInvocationQuotaMaxOrganizations(1);
        properties.setInvocationRefreshMaxIdentities(1);
        AiInvocationAdmissionService service = service();

        try (Admission admitted = service.acquire(identity(7, 29), "hash", true)) {
            admitted.commitLeaderInvocation();
            admitted.completeLeader(LeaderOutcome.CACHE_READY);
        }
        clock.advance(Duration.ofMinutes(10));
        currentOrg.set(12);
        try (Admission admitted = service.acquire(identity(8, 30), "hash", true)) {
            assertEquals(Decision.LEADER, admitted.decision());
            admitted.commitLeaderInvocation();
            admitted.completeLeader(LeaderOutcome.CACHE_READY);
        }
        assertEquals(1, service.quotaStateSize());
        assertEquals(1, service.refreshStateSize());
    }

    @Test
    void cacheRecheckCompletionReleasesUncommittedQuotaReservation() {
        properties.setInvocationQuotaAttemptsPerOrg(1);
        AiInvocationAdmissionService service = service();

        try (Admission cacheLeader = service.acquire(identity(7, 29), "hash", false)) {
            assertEquals(Decision.LEADER, cacheLeader.decision());
            cacheLeader.completeLeader(LeaderOutcome.CACHE_READY);
        }
        try (Admission providerLeader = service.acquire(identity(7, 30), "hash", false)) {
            assertEquals(Decision.LEADER, providerLeader.decision());
            providerLeader.commitLeaderInvocation();
            providerLeader.completeLeader(LeaderOutcome.FAILED);
        }
        try (Admission rejected = service.acquire(identity(7, 31), "hash", false)) {
            assertEquals(Decision.RATE_LIMITED, rejected.decision());
            assertEquals(Rejection.ORGANIZATION_QUOTA, rejected.rejection());
        }
    }

    @Test
    void stateGrowthNeverExceedsConfiguredCapacities() {
        properties.setInvocationQuotaMaxOrganizations(3);
        properties.setInvocationRefreshMaxIdentities(3);
        properties.setInvocationMaxActiveFlights(3);
        AiInvocationAdmissionService service = service();
        List<Admission> active = new ArrayList<>();

        try {
            for (int index = 0; index < 3; index++) {
                currentOrg.set(20 + index);
                active.add(service.acquire(identity(20 + index, 100 + index), "hash", true));
            }
            currentOrg.set(23);
            try (Admission rejected = service.acquire(identity(23, 103), "hash", true)) {
                assertEquals(Decision.RATE_LIMITED, rejected.decision());
                assertEquals(Rejection.CAPACITY, rejected.rejection());
            }
            assertEquals(3, service.activeFlightCount());
            assertEquals(3, service.quotaStateSize());
            assertEquals(3, service.refreshStateSize());
        } finally {
            active.forEach(Admission::close);
        }
    }

    @Test
    void cacheIdentitySortsSubjectsAndNormalizesLanguage() {
        CacheIdentity identity = CacheIdentity.forPair(
                7, AiFeature.INTRO_RATIONALE, 42, 9, Locale.JAPAN);
        CacheIdentity blankLocale = new CacheIdentity(
                7, AiFeature.DEAL_BRIEF, List.of(29), " ");

        assertEquals(List.of(9, 42), identity.subjectIds());
        assertEquals("ja", identity.locale());
        assertEquals("en", blankLocale.locale());
        assertFalse(identity.feature().requiresImageInput());
    }

    private AiInvocationAdmissionService service() {
        return new AiInvocationAdmissionService(properties, workspaceService, clock);
    }

    private static CacheIdentity identity(int workspaceId, int subjectId) {
        return CacheIdentity.forSubject(
                workspaceId, AiFeature.DEAL_BRIEF, subjectId, Locale.ENGLISH);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
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
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }
    }
}
