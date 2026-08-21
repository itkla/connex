package ooo.klae.connex.backend.connectedaccounts;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderAccountOffboardingServiceTest {

    @Test
    void accountDeletionPurgesDisconnectedRetainedDataTombstones() {
        ProviderConnectionMapper connectionMapper = mock(ProviderConnectionMapper.class);
        ProviderConnectionLifecycleService lifecycleService =
            mock(ProviderConnectionLifecycleService.class);
        ProviderConnectionMutation connectionMutation =
            mock(ProviderConnectionMutation.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        ProviderConnection tombstone = new ProviderConnection();
        tombstone.setId(31);
        tombstone.setUserId(9);
        tombstone.setProvider("google");
        tombstone.setStatus("disconnected");
        tombstone.setProviderAccountId("google:issuer:subject");
        tombstone.setCredentialGeneration(4);
        ProviderConnection cleanup = new ProviderConnection();
        cleanup.setId(31);
        cleanup.setUserId(9);
        cleanup.setProvider("google");
        cleanup.setStatus("disconnecting");
        cleanup.setCredentialGeneration(5);
        when(connectionMapper.getByUserId(9)).thenReturn(List.of(tombstone));
        when(connectionMutation.beginDisconnect(9, "google")).thenReturn(cleanup);
        when(lifecycleService.process(cleanup)).thenReturn(true);
        ProviderAccountOffboardingService service = new ProviderAccountOffboardingService(
            connectionMapper, lifecycleService, connectionMutation, tenantWorkScope);

        service.purgeBeforeAccountDeletion(9);

        InOrder order = inOrder(connectionMutation, lifecycleService);
        order.verify(connectionMutation).beginDisconnect(9, "google");
        order.verify(lifecycleService).process(cleanup);
    }
}
