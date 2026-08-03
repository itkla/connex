package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeClaimTransactionTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowRuntimeProperties properties;

    private WorkflowRuntimeClaimTransaction service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRuntimeClaimTransaction(
            outboxMapper, runMapper, properties);
        lenient().when(properties.maxTriggerDeliveryAttempts()).thenReturn(8);
        lenient().when(properties.maxOutboxLeasesPerWorkspace()).thenReturn(2);
        lenient().when(properties.maxActiveRunsPerWorkspace()).thenReturn(4);
        lenient().when(properties.maxRunDispatches()).thenReturn(256);
        lenient().when(properties.leaseDuration())
            .thenReturn(java.time.Duration.ofMinutes(2));
    }

    @Test
    void successfulTriggerClaimFlipsThePersistedWorkspaceQueue() {
        when(outboxMapper.getNextQueueForUpdate(7)).thenReturn("trigger");
        when(outboxMapper.countActiveLeases(7)).thenReturn(0);
        when(outboxMapper.findDueIdForUpdate(7)).thenReturn(31L);
        when(outboxMapper.lease(
            eq(7), eq(31L), anyString(), eq(120L), eq(8))).thenReturn(1);
        when(outboxMapper.setNextQueue(7, "run")).thenReturn(1);

        WorkflowWorkClaim claim = service.claimNext(7);

        assertNotNull(claim);
        assertEquals(WorkflowWorkClaim.Kind.TRIGGER, claim.kind());
        assertEquals(36, claim.leaseOwner().length());
        verify(outboxMapper).lease(
            eq(7),
            eq(31L),
            eq(claim.leaseOwner()),
            eq(120L),
            eq(8));
        verify(outboxMapper).ensureWorkspaceGate(7);
        verify(outboxMapper).setNextQueue(7, "run");
    }

    @Test
    void perWorkspaceCapacityPreventsAnotherLease() {
        when(outboxMapper.getNextQueueForUpdate(7)).thenReturn("trigger");
        when(outboxMapper.countActiveLeases(7)).thenReturn(2);
        when(runMapper.countActiveRunLeases(7)).thenReturn(4);

        assertNull(service.claimNext(7));

        verify(outboxMapper, never()).findDueIdForUpdate(7);
        verify(runMapper, never()).findDueRunForUpdate(7);
    }

    @Test
    void delayWaitKindSurvivesTheLeaseHandoff() {
        WorkflowRun run = new WorkflowRun();
        run.setId(41L);
        run.setWorkspaceId(7);
        run.setStatus("waiting");
        run.setWaitKind("delay");
        when(outboxMapper.getNextQueueForUpdate(7)).thenReturn("run");
        when(runMapper.countActiveRunLeases(7)).thenReturn(0);
        when(runMapper.findDueRunForUpdate(7)).thenReturn(run);
        when(runMapper.leaseRun(
            eq(7), eq(41L), anyString(), eq(120L), eq(256))).thenReturn(1);
        when(outboxMapper.setNextQueue(7, "trigger")).thenReturn(1);

        WorkflowWorkClaim claim = service.claimNext(7);

        assertNotNull(claim);
        assertEquals(WorkflowWorkClaim.Kind.RUN, claim.kind());
        assertEquals("delay", claim.resumedWaitKind());
        verify(runMapper).leaseRun(
            eq(7),
            eq(41L),
            eq(claim.leaseOwner()),
            eq(120L),
            eq(256));
        verify(outboxMapper).setNextQueue(7, "trigger");
    }
}
