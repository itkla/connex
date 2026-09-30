package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialService;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderCaptureWorkerTest {

    private final ProviderCaptureMapper captureMapper = mock(ProviderCaptureMapper.class);
    private final ProviderConnectionMapper connectionMapper =
        mock(ProviderConnectionMapper.class);
    private final ProviderCredentialService credentialService =
        mock(ProviderCredentialService.class);
    private final ProviderCapturePolicyService policyService =
        mock(ProviderCapturePolicyService.class);
    private final ProviderCapturePagePersistence pagePersistence =
        mock(ProviderCapturePagePersistence.class);
    private final TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
    private final ProviderCaptureAdapter adapter = mock(ProviderCaptureAdapter.class);
    private final ConnectedCaptureProperties properties =
        new ConnectedCaptureProperties();
    private ProviderCaptureWorker worker;

    @BeforeEach
    void setUp() {
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
        worker = new ProviderCaptureWorker(
            captureMapper,
            connectionMapper,
            credentialService,
            policyService,
            pagePersistence,
            properties,
            tenantWorkScope,
            List.of(adapter));
    }

    @Test
    void renewsTheOwnerBoundLeaseBetweenProviderCalls() {
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

        worker.runPage(7, 31);

        String owner = claimedOwner();
        ArgumentCaptor<String> renewals = ArgumentCaptor.forClass(String.class);
        verify(captureMapper, atLeast(2)).renewSyncLease(
            eq(7), eq(31L), renewals.capture(), anyString(), anyString());
        for (String renewalOwner : renewals.getAllValues()) {
            assertEquals(owner, renewalOwner);
        }
        ArgumentCaptor<String> committedOwner = ArgumentCaptor.forClass(String.class);
        verify(pagePersistence).commit(
            eq(7),
            eq(31L),
            committedOwner.capture(),
            any(ProviderCapturePage.class),
            any(CaptureExecutionPolicy.class),
            eq("owner@example.test"));
        assertEquals(owner, committedOwner.getValue());
    }

    @Test
    void failedInitialRenewalStopsBeforeFetchingAndReportsTheClaimedOwner() {
        when(captureMapper.renewSyncLease(
                eq(7), eq(31L), anyString(), anyString(), anyString()))
            .thenReturn(0);

        worker.runPage(7, 31);

        String owner = claimedOwner();
        verify(captureMapper).renewSyncLease(
            eq(7), eq(31L), eq(owner), anyString(), anyString());
        verify(adapter, never()).fetch(any());
        verifyNoInteractions(pagePersistence);
        verify(captureMapper).saveSyncFailure(
            eq(7), eq(31L), eq(owner), eq("retrying"), eq("lease_lost"), anyString());
    }

    @Test
    void failedCallbackRenewalStopsThePageAndReportsTheClaimedOwner() {
        when(captureMapper.renewSyncLease(
                eq(7), eq(31L), anyString(), anyString(), anyString()))
            .thenReturn(1, 0);
        AtomicBoolean continued = new AtomicBoolean();
        when(adapter.fetch(any())).thenAnswer(invocation -> {
            ProviderCaptureRequest request = invocation.getArgument(0);
            request.lease().renew();
            continued.set(true);
            return new ProviderCapturePage(List.of(), null, "calendar-cursor", null);
        });

        worker.runPage(7, 31);

        String owner = claimedOwner();
        verify(adapter).fetch(any());
        verify(captureMapper, times(2)).renewSyncLease(
            eq(7), eq(31L), eq(owner), anyString(), anyString());
        assertFalse(continued.get());
        verifyNoInteractions(pagePersistence);
        verify(captureMapper).saveSyncFailure(
            eq(7), eq(31L), eq(owner), eq("retrying"), eq("lease_lost"), anyString());
    }

    private String claimedOwner() {
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(captureMapper).claimSync(
            eq(7), eq(31L), owner.capture(), anyString(), anyString());
        assertNotNull(owner.getValue());
        assertFalse(owner.getValue().isBlank());
        return owner.getValue();
    }
}
