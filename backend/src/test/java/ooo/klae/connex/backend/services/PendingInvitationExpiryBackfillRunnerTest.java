package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper.PendingDeadlineRow;
import ooo.klae.connex.backend.services.PendingInvitationExpiryBackfillTransaction.Batch;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Pins bounded sweeps, retry cursors, generation revalidation and transaction independence. */
class PendingInvitationExpiryBackfillRunnerTest {
    private WorkspaceMapper mapper;
    private TenantWorkScope scope;
    private PendingInvitationExpiryBackfillTransaction transaction;
    private PendingInvitationExpiryBackfillRunner runner;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkspaceMapper.class);
        scope = mock(TenantWorkScope.class);
        transaction = mock(PendingInvitationExpiryBackfillTransaction.class);
        runner = new PendingInvitationExpiryBackfillRunner(mapper, scope, transaction);
        when(scope.unroutedRead(any())).thenAnswer(call -> call.<ControlPlaneRead<?>>getArgument(0).read());
        when(scope.unrouted(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
    }

    @Test
    void sweepIsBoundedAndResumesOnlyCommittedPages() {
        when(mapper.findWorkspaceIdsPage(0, 1)).thenReturn(List.of(7));
        when(transaction.backfillPage(eq(7), anyInt())).thenAnswer(call ->
            new Batch(call.<Integer>getArgument(1) + 100, 100, 100));
        runner.reconcile();
        verify(transaction, times(4)).backfillPage(eq(7), anyInt());
        verify(transaction).backfillPage(7, 300);
        runner.reconcile();
        verify(transaction, times(8)).backfillPage(eq(7), anyInt());
        verify(transaction).backfillPage(7, 700);
        verify(scope, times(8)).unrouted(any());
    }

    @Test
    void failedPageRetriesItsCursorAndCompletedSweepWraps() {
        when(mapper.findWorkspaceIdsPage(0, 1)).thenReturn(List.of(7));
        when(mapper.findWorkspaceIdsPage(7, 1)).thenReturn(List.of());
        when(transaction.backfillPage(7, 0)).thenReturn(new Batch(100, 100, 90));
        when(transaction.backfillPage(7, 100)).thenThrow(new IllegalStateException("page failed"))
            .thenReturn(new Batch(101, 1, 1));
        runner.reconcile();
        runner.reconcile();
        verify(transaction, times(2)).backfillPage(7, 100);
        when(transaction.backfillPage(7, 0)).thenReturn(new Batch(0, 0, 0));
        runner.reconcile();
        verify(transaction, times(2)).backfillPage(7, 0);
    }

    @Test
    void pageOnlyWritesPendingCandidatesAndRetainsExactGeneration() {
        var writer = new PendingInvitationExpiryBackfillTransaction(mapper);
        when(mapper.findPendingDeadlineWindow(7, 0, 100)).thenReturn(List.of(
            new PendingDeadlineRow(1, 11L, false), new PendingDeadlineRow(3, 13L, true)));
        when(mapper.backfillPendingDeadline(7, 3, 13L)).thenReturn(1);
        assertEquals(new Batch(3, 2, 1), writer.backfillPage(7, 0));
        verify(mapper, never()).backfillPendingDeadline(7, 1, 11L);
        assertThrows(IllegalArgumentException.class, () -> writer.backfillPage(0, 0));
        when(mapper.findPendingDeadlineWindow(7, 3, 100)).thenReturn(List.of(new PendingDeadlineRow(3, 13L, true)));
        assertThrows(IllegalStateException.class, () -> writer.backfillPage(7, 3));
    }

    @Test
    void pageHasIndependentBoundedReadCommittedTransaction() throws Exception {
        var annotation = PendingInvitationExpiryBackfillTransaction.class.getMethod("backfillPage", int.class, int.class)
            .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
        assertEquals(Isolation.READ_COMMITTED, annotation.isolation());
        assertEquals(10, annotation.timeout());
    }
}
