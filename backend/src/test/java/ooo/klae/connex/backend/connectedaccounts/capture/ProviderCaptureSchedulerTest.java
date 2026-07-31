package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.dto.ProviderCaptureSyncRef;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderCaptureSchedulerTest {

    @Test
    void boundedSweepsRotateTheStartingWorkspace() {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        ProviderCaptureMapper captureMapper = mock(ProviderCaptureMapper.class);
        ProviderCaptureWorker worker = mock(ProviderCaptureWorker.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        ConnectedCaptureProperties properties = new ConnectedCaptureProperties();
        properties.setSchedulerBatchSize(1);
        when(workspaceMapper.findWorkspaceIdsPage(0, 1)).thenReturn(List.of(1));
        when(workspaceMapper.findWorkspaceIdsPage(1, 1)).thenReturn(List.of(2));
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<List<Integer>>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<List<Integer>>>getArgument(0).get());
        when(tenantWorkScope.inWorkspace(
                anyInt(),
                org.mockito.ArgumentMatchers
                    .<Supplier<List<ProviderCaptureSyncRef>>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<List<ProviderCaptureSyncRef>>>getArgument(1).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inWorkspace(anyInt(), any(Runnable.class));
        when(captureMapper.findDueSyncRefs(eq(1), anyString(), eq(1)))
            .thenReturn(List.of(new ProviderCaptureSyncRef(1, 101)));
        when(captureMapper.findDueSyncRefs(eq(2), anyString(), eq(1)))
            .thenReturn(List.of(new ProviderCaptureSyncRef(2, 202)));
        ProviderCaptureScheduler scheduler = new ProviderCaptureScheduler(
            workspaceMapper,
            captureMapper,
            worker,
            tenantWorkScope,
            properties);

        scheduler.poll();
        scheduler.poll();

        InOrder order = inOrder(worker);
        order.verify(worker).runPage(1, 101);
        order.verify(worker).runPage(2, 202);
    }
}
