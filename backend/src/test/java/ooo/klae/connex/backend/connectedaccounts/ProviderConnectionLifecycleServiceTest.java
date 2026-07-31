package ooo.klae.connex.backend.connectedaccounts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderConnectionLifecycleServiceTest {

    @Test
    void everyTenantCatalogIsPurgedBeforeRevocationAndCredentialDeletion() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        ProviderCapturePurgeService purgeService =
            mock(ProviderCapturePurgeService.class);
        PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
        ProviderConnectionLifecyclePersistence persistence =
            mock(ProviderConnectionLifecyclePersistence.class);
        UserProviderSecretCipher cipher = mock(UserProviderSecretCipher.class);
        ConnectedAccountProviders providers = new ConnectedAccountProviders(
            new ConnectedAccountProperties());
        ProviderTokenClient tokenClient = mock(ProviderTokenClient.class);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inLifecycleWorkspace(
            anyInt(), any(Runnable.class));
        when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        when(workspaceMapper.findWorkspaceIdsLifecyclePage(0, 50))
            .thenReturn(List.of(3, 5));
        ProviderConnection connection = connection();
        when(connectionMapper.getById(31)).thenReturn(connection);
        when(connectionMapper.getByIdForShare(31)).thenReturn(connection);
        when(connectionMapper.claimCaptureReconcile(
                anyInt(), anyLong(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                connection.setCaptureReconcileLeaseOwner(
                    invocation.getArgument(2));
                return 1;
            });
        when(connectionMapper.advanceCaptureReconcile(
                anyInt(), anyLong(), anyString(), anyInt(), anyBoolean()))
            .thenReturn(1);
        when(cipher.decryptTokenBundle(
                "google", 9, "credential-ref"))
            .thenReturn("{\"refreshToken\":\"refresh-token\"}");
        when(persistence.finish(connection)).thenReturn(true);
        ProviderConnectionLifecycleService service =
            new ProviderConnectionLifecycleService(
                connectionMapper,
                workspaceMapper,
                tenantWorkScope,
                purgeService,
                transactionManager,
                persistence,
                cipher,
                providers,
                tokenClient,
                new ObjectMapper(),
                new ConnectedCaptureProperties());

        assertTrue(service.process(connection));

        InOrder order = inOrder(purgeService, tokenClient, persistence);
        order.verify(purgeService).purge(3, 9, "google");
        order.verify(purgeService).purge(5, 9, "google");
        order.verify(tokenClient).revoke(
            "https://oauth2.googleapis.com/revoke", "refresh-token");
        order.verify(persistence).finish(connection);
        verify(connectionMapper).claimCaptureReconcile(
            eq(31), eq(4L), anyString(), anyString(), anyString());
    }

    @Test
    void fullPurgePageAdvancesCursorWithoutDeletingCredential() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        ProviderCapturePurgeService purgeService =
            mock(ProviderCapturePurgeService.class);
        PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
        ProviderConnectionLifecyclePersistence persistence =
            mock(ProviderConnectionLifecyclePersistence.class);
        ProviderConnection connection = connection();
        ConnectedCaptureProperties properties =
            new ConnectedCaptureProperties();
        properties.setSchedulerBatchSize(2);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inLifecycleWorkspace(
            anyInt(), any(Runnable.class));
        when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        when(connectionMapper.getById(31)).thenReturn(connection);
        when(connectionMapper.getByIdForShare(31)).thenReturn(connection);
        when(connectionMapper.claimCaptureReconcile(
                anyInt(), anyLong(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                connection.setCaptureReconcileLeaseOwner(
                    invocation.getArgument(2));
                return 1;
            });
        when(connectionMapper.advanceCaptureReconcile(
                anyInt(), anyLong(), anyString(), anyInt(), anyBoolean()))
            .thenReturn(1);
        when(workspaceMapper.findWorkspaceIdsLifecyclePage(0, 2))
            .thenReturn(List.of(3, 5));
        ProviderConnectionLifecycleService service =
            new ProviderConnectionLifecycleService(
                connectionMapper,
                workspaceMapper,
                tenantWorkScope,
                purgeService,
                transactionManager,
                persistence,
                mock(UserProviderSecretCipher.class),
                new ConnectedAccountProviders(
                    new ConnectedAccountProperties()),
                mock(ProviderTokenClient.class),
                new ObjectMapper(),
                properties);

        assertFalse(service.process(connection));

        verify(connectionMapper).advanceCaptureReconcile(
            eq(31), eq(4L), anyString(), eq(5), eq(false));
        verify(persistence, never()).finish(any());
    }

    private static ProviderConnection connection() {
        ProviderConnection connection = new ProviderConnection();
        connection.setId(31);
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus("disconnecting");
        connection.setCredentialRef("credential-ref");
        connection.setCredentialGeneration(4);
        connection.setCaptureReconcileRequired(true);
        return connection;
    }
}
