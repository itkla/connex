package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Unit coverage of one heartbeat tick's observation order, failure discipline, and schedule. */
class AiRunLeaseHeartbeatTest {

    private static final int WORKSPACE = 11;
    private static final AiRunLease LEASE = new AiRunLease(
            new AiRunLeaseKey(WORKSPACE, AiRunLeaseSubject.CHAT_TURN, 5L), "owner-a", 3L);

    private final AiRunLeaseService leaseService = mock(AiRunLeaseService.class);
    private final TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
    private final AiProperties properties = new AiProperties();
    private final AtomicBoolean subjectRunning = new AtomicBoolean(true);
    private final StubSubjectHandler handler = new StubSubjectHandler(subjectRunning);

    private AiRunLeaseHeartbeat heartbeat;

    @AfterEach
    void stopScheduler() {
        if (heartbeat != null) {
            heartbeat.shutdown();
        }
    }

    @Test
    void aTickReadsSubjectLivenessThroughTheHandlerBeforeRenewing() {
        routeThroughWorkspace();
        when(leaseService.renew(LEASE)).thenReturn(AiRunLeaseOutcome.HELD);
        AiRunLeaseGuard guard = guard();

        assertFalse(newHeartbeat().beat(LEASE, guard));

        assertEquals(1, handler.calls());
        assertTrue(guard.reason().isEmpty());
        verify(leaseService).renew(LEASE);
        verify(tenantWorkScope).inWorkspace(eq(WORKSPACE), ArgumentMatchers.<Supplier<Optional<String>>>any());
    }

    @Test
    void aRenewalThatThrowsIsRetriedRatherThanTreatedAsLoss() {
        routeThroughWorkspace();
        when(leaseService.renew(LEASE))
                .thenThrow(new IllegalStateException("connection reset"))
                .thenReturn(AiRunLeaseOutcome.HELD);
        AiRunLeaseGuard guard = guard();
        AiRunLeaseHeartbeat beating = newHeartbeat();

        assertFalse(beating.beat(LEASE, guard));
        assertTrue(guard.reason().isEmpty());

        assertFalse(beating.beat(LEASE, guard));
        assertTrue(guard.reason().isEmpty());
        verify(leaseService, times(2)).renew(LEASE);
    }

    @Test
    void anAuthoritativeLossStopsTheOwnerAndTheScheduleStopsAskingAgain() {
        routeThroughWorkspace();
        when(leaseService.renew(LEASE)).thenReturn(AiRunLeaseOutcome.LOST);
        AiRunLeaseGuard guard = guard();
        AiRunLeaseHeartbeat beating = newHeartbeat();

        assertTrue(beating.beat(LEASE, guard));
        assertEquals(Optional.of(AiRunLeaseGuard.LEASE_LOST), guard.reason());

        assertTrue(beating.beat(LEASE, guard));
        verify(leaseService, times(1)).renew(LEASE);
    }

    @Test
    void aSubjectThatIsNoLongerRunningStopsTheOwnerWithoutRenewing() {
        routeThroughWorkspace();
        subjectRunning.set(false);
        AiRunLeaseGuard guard = guard();

        assertTrue(newHeartbeat().beat(LEASE, guard));

        assertEquals(Optional.of(AiRunLeaseGuard.SUBJECT_STOPPED), guard.reason());
        verify(leaseService, never()).renew(any());
        verify(tenantWorkScope)
                .inWorkspace(eq(WORKSPACE), ArgumentMatchers.<Supplier<Optional<String>>>any());
    }

    @Test
    void theScheduleRenewsUntilItsHandleIsClosed() throws Exception {
        routeThroughWorkspace();
        when(leaseService.renew(LEASE)).thenReturn(AiRunLeaseOutcome.HELD);
        properties.setRunLeaseHeartbeatInterval(Duration.ofMillis(50));
        AiRunLeaseHeartbeat beating = newHeartbeat();
        AiRunLeaseGuard guard = guard();

        AutoCloseable handle = beating.start(LEASE, guard);
        awaitRenewals(2);
        handle.close();
        int settled = Mockito.mockingDetails(leaseService).getInvocations().size();
        Thread.sleep(400L);

        assertTrue(
                Mockito.mockingDetails(leaseService).getInvocations().size() <= settled + 1,
                "A closed heartbeat handle must stop renewing");
        assertTrue(guard.reason().isEmpty());
    }

    @Test
    void aSlowFailingRenewalIsFollowedPromptlyRatherThanAWholeIntervalLater() throws Exception {
        routeThroughWorkspace();
        AtomicLong attempts = new AtomicLong();
        AtomicLong firstReturnedAt = new AtomicLong();
        AtomicLong secondStartedAt = new AtomicLong();
        when(leaseService.renew(LEASE)).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1L) {
                Thread.sleep(1_500L);
                firstReturnedAt.set(System.nanoTime());
                throw new IllegalStateException("statement timed out");
            }
            secondStartedAt.compareAndSet(0L, System.nanoTime());
            return AiRunLeaseOutcome.HELD;
        });
        properties.setRunLeaseHeartbeatInterval(Duration.ofSeconds(1));
        AiRunLeaseHeartbeat beating = newHeartbeat();

        AutoCloseable handle = beating.start(LEASE, guard());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (secondStartedAt.get() == 0L && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
        handle.close();

        assertTrue(secondStartedAt.get() != 0L, "The heartbeat never retried after a slow failure");
        long gapMillis =
                Duration.ofNanos(secondStartedAt.get() - firstReturnedAt.get()).toMillis();
        assertTrue(
                gapMillis < 500L,
                "The attempt after a slow failure began " + gapMillis + " ms after it returned;"
                        + " a fixed delay would wait the whole one-second interval and can carry"
                        + " the retry past the lease deadline");
    }

    @Test
    void theHeartbeatPoolIsSizedFromTheConfiguredThreadCount() {
        properties.setRunLeaseHeartbeatThreads(6);

        assertEquals(6, newHeartbeat().poolSize());
    }

    /**
     * Without a handler there is no liveness read, so renewing anyway would leave the documented
     * cross-instance stop bound silently absent for that subject kind — the owner would keep
     * working after another instance had cancelled the run.
     */
    @Test
    void aSubjectKindNoHandlerOwnsStopsTheRunRatherThanRenewingBlind() {
        routeThroughWorkspace();
        heartbeat = new AiRunLeaseHeartbeat(leaseService, tenantWorkScope, properties, List.of());
        AiRunLeaseGuard guard = guard();

        assertTrue(heartbeat.beat(LEASE, guard));

        assertEquals(Optional.of(AiRunLeaseGuard.NO_SUBJECT_HANDLER), guard.reason());
        verify(leaseService, never()).renew(any());
    }

    @Test
    void aHeartbeatIsRefusedOutrightForASubjectKindNoHandlerOwns() {
        heartbeat = new AiRunLeaseHeartbeat(leaseService, tenantWorkScope, properties, List.of());

        assertThrows(IllegalStateException.class, () -> heartbeat.start(LEASE, guard()));
    }

    /**
     * The guard's window must run from the instant the renewal was issued: MySQL wrote its own
     * deadline part-way through the round trip, so anchoring on completion would put the local
     * fence after the database's and let a settler take the run over while the guard still
     * answered healthy.
     */
    @Test
    void theSelfFenceIsAnchoredBeforeTheRenewalRoundTripNotAfterIt() {
        routeThroughWorkspace();
        AtomicLong nanos = new AtomicLong();
        AiRunLeaseGuard guard = new AiRunLeaseGuard(properties.getRunLeaseTtl(), nanos::get);
        when(leaseService.renew(LEASE)).thenAnswer(invocation -> {
            nanos.set(Duration.ofSeconds(10).toNanos());
            return AiRunLeaseOutcome.HELD;
        });

        assertFalse(newHeartbeat().beat(LEASE, guard));

        nanos.set(properties.getRunLeaseTtl().toNanos() + 1L);
        assertTrue(guard.isStopped(), "The self-fence must expire one TTL after the renewal issued");
        assertEquals(Optional.of(AiRunLeaseGuard.RENEW_GAP), guard.reason());
    }

    /**
     * Starting a heartbeat may never move the anchor the claim set. The claim reads the guard's
     * clock immediately before it writes the lease, so that anchor is at or before the deadline
     * MySQL assigned; a worker descheduled between the claim's commit and this call would
     * otherwise re-anchor on a later reading and report itself healthy over a lease a settler is
     * already entitled to take over.
     */
    @Test
    void startingAHeartbeatNeverMovesTheAnchorTheClaimSet() throws Exception {
        AtomicLong nanos = new AtomicLong();
        AiRunLeaseGuard guard = new AiRunLeaseGuard(properties.getRunLeaseTtl(), nanos::get);
        guard.recordRenewal(guard.clockNanos());
        nanos.set(properties.getRunLeaseTtl().toNanos() + 1L);

        AutoCloseable handle = newHeartbeat().start(LEASE, guard);

        assertTrue(
                guard.isStopped(),
                "A worker paused past the lease lifetime must not be re-anchored by starting"
                        + " its heartbeat");
        assertEquals(Optional.of(AiRunLeaseGuard.RENEW_GAP), guard.reason());
        handle.close();
    }

    private void awaitRenewals(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Mockito.mockingDetails(leaseService).getInvocations().size() >= expected) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Heartbeat did not renew " + expected + " times within 10s");
    }

    private AiRunLeaseHeartbeat newHeartbeat() {
        heartbeat = new AiRunLeaseHeartbeat(
                leaseService, tenantWorkScope, properties, List.of(handler));
        return heartbeat;
    }

    private AiRunLeaseGuard guard() {
        return new AiRunLeaseGuard(properties.getRunLeaseTtl());
    }

    private void routeThroughWorkspace() {
        when(tenantWorkScope.inWorkspace(
                        eq(WORKSPACE), ArgumentMatchers.<Supplier<Optional<String>>>any()))
                .thenAnswer(invocation ->
                        invocation.<Supplier<Optional<String>>>getArgument(1).get());
    }

    private static final class StubSubjectHandler implements AiRunLeaseSubjectHandler {
        private final AtomicBoolean running;
        private int calls;

        private StubSubjectHandler(AtomicBoolean running) {
            this.running = running;
        }

        @Override
        public AiRunLeaseSubject subject() {
            return AiRunLeaseSubject.CHAT_TURN;
        }

        @Override
        public boolean isSubjectRunning(int workspaceId, long subjectId) {
            calls++;
            return running.get();
        }

        @Override
        public void settleOrphan(int workspaceId, long subjectId, AiRunLease takeover) {
            throw new UnsupportedOperationException("Settlement is not exercised by this test");
        }

        private int calls() {
            return calls;
        }
    }
}
