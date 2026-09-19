package ooo.klae.connex.backend.ai.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theHeartbeatPoolIsSizedFromTheConfiguredThreadCount() {
        properties.setRunLeaseHeartbeatThreads(6);

        assertEquals(6, newHeartbeat().poolSize());
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
