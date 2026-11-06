package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.mappers.RecordSearchMapper;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Kind;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class RecordSearchReconcilerTest {
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantWorkScope scope = mock(TenantWorkScope.class);
    private final RecordSearchMapper records = mock(RecordSearchMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RecordSearchReconciler reconciler = new RecordSearchReconciler(workspaces, scope, records, transactions);

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> invocation.<ControlPlaneRead<?>>getArgument(0).read())
            .when(scope).unroutedRead(any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(scope).inWorkspace(anyInt(), any(Runnable.class));
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    @Test
    void workspaceWatermarkForcesWrapDespiteNewerWorkspaceArrivals() {
        when(workspaces.findLastWorkspaceId()).thenReturn(100, 200);
        when(workspaces.findWorkspaceIdsThroughPage(0, 100, 25))
            .thenReturn(IntStream.rangeClosed(1, 25).boxed().toList());
        when(workspaces.findWorkspaceIdsThroughPage(25, 100, 25)).thenReturn(List.of(100));
        when(workspaces.findWorkspaceIdsThroughPage(0, 200, 25)).thenReturn(List.of(1));
        reconciler.sweep();
        reconciler.sweep();
        reconciler.sweep();
        verify(workspaces, times(2)).findLastWorkspaceId();
        verify(workspaces).findWorkspaceIdsThroughPage(0, 100, 25);
        verify(workspaces).findWorkspaceIdsThroughPage(25, 100, 25);
        verify(workspaces).findWorkspaceIdsThroughPage(0, 200, 25);
        verify(scope, times(2)).inWorkspace(eq(1), any(Runnable.class));
    }

    @Test
    void failedPersonPageDoesNotStarveCompanyAndEveryPageOwnsATransaction() {
        when(workspaces.findLastWorkspaceId()).thenReturn(11);
        when(workspaces.findWorkspaceIdsThroughPage(0, 11, 25)).thenReturn(List.of(11));
        when(records.maxId(11, Kind.PERSON)).thenThrow(new IllegalStateException("Injected failure"));
        when(records.maxId(11, Kind.COMPANY)).thenReturn(1);
        when(records.pageIds(11, Kind.COMPANY, 0, 1, 500)).thenReturn(List.of(1));
        when(records.findMissing(11, Kind.COMPANY, 0, 1, 500)).thenReturn(List.of());
        reconciler.sweep();
        verify(records).findMissing(11, Kind.COMPANY, 0, 1, 500);
        verify(transactions).rollback(any(TransactionStatus.class));
        verify(transactions).commit(any(TransactionStatus.class));
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definitions.capture());
        for (TransactionDefinition definition : definitions.getAllValues()) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
        }
    }

    @Test
    void commitFailureDoesNotPublishTheAdvancedRecordCursor() {
        when(workspaces.findLastWorkspaceId()).thenReturn(11);
        when(workspaces.findWorkspaceIdsThroughPage(0, 11, 25)).thenReturn(List.of(11));
        when(records.maxId(11, Kind.PERSON)).thenReturn(501);
        when(records.pageIds(11, Kind.PERSON, 0, 501, 500))
            .thenReturn(IntStream.rangeClosed(1, 500).boxed().toList());
        when(records.findMissing(11, Kind.PERSON, 0, 500, 500)).thenReturn(List.of());
        doThrow(new IllegalStateException("Injected commit failure")).doNothing()
            .when(transactions).commit(any(TransactionStatus.class));
        reconciler.sweep();
        reconciler.sweep();
        verify(records, times(2)).pageIds(11, Kind.PERSON, 0, 501, 500);
    }
}
