package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.NotificationReconciliationService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private NotificationReconciliationService reconciliationService;

    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(
            workspaceMapper, tenantWorkScope, reconciliationService);
    }

    @Test
    void enumeratesUnroutedThenReconcilesEachWorkspaceSequentially() {
        AtomicBoolean enumeratingUnrouted = new AtomicBoolean();
        when(workspaceMapper.findWorkspaceIds()).thenAnswer(invocation -> {
            assertTrue(enumeratingUnrouted.get());
            return List.of(3, 7);
        });
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<List<Integer>> work = invocation.getArgument(0);
            enumeratingUnrouted.set(true);
            try {
                return work.get();
            } finally {
                enumeratingUnrouted.set(false);
            }
        });
        doAnswer(invocation -> {
            assertFalse(enumeratingUnrouted.get());
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inWorkspace(anyInt(), any(Runnable.class));

        scheduler.reconcileAndPurge();

        InOrder order = inOrder(tenantWorkScope, workspaceMapper, reconciliationService);
        order.verify(tenantWorkScope).unrouted(any());
        order.verify(workspaceMapper).findWorkspaceIds();
        order.verify(tenantWorkScope).inWorkspace(eq(3), any(Runnable.class));
        order.verify(reconciliationService).reconcileWorkspace(3, true);
        order.verify(reconciliationService).purgeWorkspace(3);
        order.verify(tenantWorkScope).inWorkspace(eq(7), any(Runnable.class));
        order.verify(reconciliationService).reconcileWorkspace(7, true);
        order.verify(reconciliationService).purgeWorkspace(7);
        order.verifyNoMoreInteractions();
        verify(tenantWorkScope).unrouted(any());
        verify(workspaceMapper).findWorkspaceIds();
        verify(tenantWorkScope, times(2)).inWorkspace(anyInt(), any(Runnable.class));
        verify(reconciliationService, times(2)).reconcileWorkspace(anyInt(), eq(true));
        verify(reconciliationService, times(2)).purgeWorkspace(anyInt());
        verifyNoMoreInteractions(tenantWorkScope, workspaceMapper, reconciliationService);
    }

    @Test
    void continuesAfterOneWorkspaceCannotBeRouted() {
        when(workspaceMapper.findWorkspaceIds()).thenReturn(List.of(3, 7));
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation ->
            invocation.<Supplier<List<Integer>>>getArgument(0).get());
        doThrow(new IllegalStateException("unavailable"))
            .when(tenantWorkScope).inWorkspace(eq(3), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inWorkspace(eq(7), any(Runnable.class));

        scheduler.reconcileAndPurge();

        verify(reconciliationService, never()).reconcileWorkspace(3, true);
        verify(reconciliationService, never()).purgeWorkspace(3);
        verify(reconciliationService).reconcileWorkspace(7, true);
        verify(reconciliationService).purgeWorkspace(7);
    }
}
