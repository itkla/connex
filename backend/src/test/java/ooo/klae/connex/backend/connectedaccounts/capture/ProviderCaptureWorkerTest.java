package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialService;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderCaptureWorkerTest {

    @Test
    void renewsTheOwnerBoundLeaseBetweenProviderCalls() {
        ProviderCaptureMapper captureMapper = mock(ProviderCaptureMapper.class);
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        ProviderCredentialService credentialService =
            mock(ProviderCredentialService.class);
        ProviderCapturePolicyService policyService =
            mock(ProviderCapturePolicyService.class);
        ProviderCapturePagePersistence pagePersistence =
            mock(ProviderCapturePagePersistence.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        ProviderCaptureAdapter adapter = mock(ProviderCaptureAdapter.class);
        ConnectedCaptureProperties properties =
            new ConnectedCaptureProperties();
        ProviderCaptureSyncState state = new ProviderCaptureSyncState();
        state.setId(31);
        state.setWorkspaceId(7);
        state.setUserId(9);
        state.setProvider("google");
        state.setStream("calendar");
        state.setCredentialGeneration(4);
        ProviderConnection connection = new ProviderConnection();
        connection.setStatus("connected");
        connection.setCredentialGeneration(4);
        connection.setProviderAccountEmail("owner@example.test");
        when(captureMapper.claimSync(
                eq(7), eq(31L), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                state.setLeaseOwner(invocation.getArgument(2));
                return 1;
            });
        when(captureMapper.getSyncState(7, 31)).thenReturn(state);
        when(captureMapper.renewSyncLease(
                eq(7), eq(31L), anyString(), anyString(), anyString()))
            .thenReturn(1);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        when(connectionMapper.getByUserAndProvider(9, "google"))
            .thenReturn(connection);
        when(credentialService.accessToken(connection)).thenReturn("token");
        when(policyService.effectivePolicy(7, 9, "google", connection))
            .thenReturn(new CaptureExecutionPolicy(
                true,
                true,
                false,
                false,
                90,
                false,
                "review",
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                1));
        when(adapter.provider()).thenReturn("google");
        when(adapter.fetch(any())).thenAnswer(invocation -> {
            ProviderCaptureRequest request = invocation.getArgument(0);
            request.lease().renew();
            ProviderCaptureItem item = mock(ProviderCaptureItem.class);
            request.bodyAccess().allows(item);
            verify(pagePersistence).bodyAllowed(
                eq(item),
                any(CaptureExecutionPolicy.class),
                eq("owner@example.test"));
            return new ProviderCapturePage(
                List.of(), null, "calendar-cursor", null);
        });
        ProviderCaptureWorker worker = new ProviderCaptureWorker(
            captureMapper,
            connectionMapper,
            credentialService,
            policyService,
            pagePersistence,
            properties,
            tenantWorkScope,
            List.of(adapter));

        worker.runPage(7, 31);

        verify(captureMapper, atLeast(2)).renewSyncLease(
            eq(7), eq(31L), anyString(), anyString(), anyString());
        verify(pagePersistence).commit(
            eq(7),
            eq(31L),
            anyString(),
            any(ProviderCapturePage.class),
            any(CaptureExecutionPolicy.class),
            eq("owner@example.test"));
    }
}
