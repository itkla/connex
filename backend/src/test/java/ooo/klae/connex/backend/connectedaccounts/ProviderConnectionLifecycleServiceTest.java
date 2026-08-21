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
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderConnectionLifecycleServiceTest {

    @Test
    void everyTenantCatalogIsPurgedBeforeRevocationAndCredentialDeletion() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        TenantLifecycleControlMapper lifecycleControlMapper =
            mock(TenantLifecycleControlMapper.class);
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
        AuditService auditService = mock(AuditService.class);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(tenantWorkScope).inLifecycleWorkspace(
            anyInt(), any(Runnable.class));
        when(tenantWorkScope.inLifecycleWorkspace(
                anyInt(), org.mockito.ArgumentMatchers.<Supplier<Boolean>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Boolean>>getArgument(1).get());
        when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        when(workspaceMapper.findWorkspaceIdsLifecyclePage(0, 50))
            .thenReturn(List.of(3, 5));
        when(lifecycleControlMapper.findWorkspaceOrgIdForLifecycle(3)).thenReturn(13);
        when(lifecycleControlMapper.findWorkspaceOrgIdForLifecycle(5)).thenReturn(15);
        when(purgeService.hasResiduals(3, 9, "google")).thenReturn(true);
        when(purgeService.hasResiduals(5, 9, "google")).thenReturn(true);
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
                userMapper(),
                workspaceMapper,
                lifecycleControlMapper,
                tenantWorkScope,
                purgeService,
                transactionManager,
                persistence,
                cipher,
                providers,
                tokenClient,
                new ObjectMapper(),
                new ConnectedCaptureProperties(),
                auditService);

        assertTrue(service.process(connection));

        InOrder order = inOrder(auditService, purgeService, tokenClient, persistence);
        order.verify(auditService).recordStrictIndependentScoped(
            eq("provider.capture.purge"), eq("user"), eq(9), eq(3), eq(13),
            eq("google"), anyString(), any());
        order.verify(purgeService).purge(3, 9, "google");
        order.verify(auditService).recordIndependentScoped(
            eq("provider.capture.purge.complete"), eq("user"), eq(9), eq(3), eq(13),
            eq("google"), anyString(), any());
        order.verify(auditService).recordStrictIndependentScoped(
            eq("provider.capture.purge"), eq("user"), eq(9), eq(5), eq(15),
            eq("google"), anyString(), any());
        order.verify(purgeService).purge(5, 9, "google");
        order.verify(auditService).recordIndependentScoped(
            eq("provider.capture.purge.complete"), eq("user"), eq(9), eq(5), eq(15),
            eq("google"), anyString(), any());
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
        TenantLifecycleControlMapper lifecycleControlMapper =
            mock(TenantLifecycleControlMapper.class);
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
        when(tenantWorkScope.inLifecycleWorkspace(
                anyInt(), org.mockito.ArgumentMatchers.<Supplier<Boolean>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Boolean>>getArgument(1).get());
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
                userMapper(),
                workspaceMapper,
                lifecycleControlMapper,
                tenantWorkScope,
                purgeService,
                transactionManager,
                persistence,
                mock(UserProviderSecretCipher.class),
                new ConnectedAccountProviders(
                    new ConnectedAccountProperties()),
                mock(ProviderTokenClient.class),
                new ObjectMapper(),
                properties,
                mock(AuditService.class));

        assertFalse(service.process(connection));

        verify(connectionMapper).advanceCaptureReconcile(
            eq(31), eq(4L), anyString(), eq(5), eq(false));
        verify(persistence, never()).finish(any());
    }

    @Test
    void ordinaryRevocationSkipsTenantPurgeAndRetainsTombstone() {
        ProviderConnectionMapper connectionMapper =
            mock(ProviderConnectionMapper.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        ProviderCapturePurgeService purgeService =
            mock(ProviderCapturePurgeService.class);
        ProviderConnectionLifecyclePersistence persistence =
            mock(ProviderConnectionLifecyclePersistence.class);
        UserProviderSecretCipher cipher = mock(UserProviderSecretCipher.class);
        ProviderTokenClient tokenClient = mock(ProviderTokenClient.class);
        ProviderConnection connection = connection();
        connection.setStatus("revoking");
        connection.setCaptureReconcileRequired(false);
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        when(connectionMapper.getById(31)).thenReturn(connection);
        when(connectionMapper.claimRevocationAttempt(31, 4)).thenReturn(1);
        when(cipher.decryptTokenBundle("google", 9, "credential-ref"))
            .thenReturn("{\"refreshToken\":\"refresh-token\"}");
        when(persistence.finishRevocation(connection)).thenReturn(true);
        ProviderConnectionLifecycleService service =
            new ProviderConnectionLifecycleService(
                connectionMapper,
                userMapper(),
                mock(WorkspaceMapper.class),
                mock(TenantLifecycleControlMapper.class),
                tenantWorkScope,
                purgeService,
                mock(PlatformTransactionManager.class),
                persistence,
                cipher,
                new ConnectedAccountProviders(
                    new ConnectedAccountProperties()),
                tokenClient,
                new ObjectMapper(),
                new ConnectedCaptureProperties(),
                mock(AuditService.class));

        assertTrue(service.process(connection));

        InOrder order = inOrder(tokenClient, persistence);
        order.verify(tokenClient).revoke(
            "https://oauth2.googleapis.com/revoke", "refresh-token");
        order.verify(persistence).finishRevocation(connection);
        verify(purgeService, never()).purge(anyInt(), anyInt(), anyString());
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

    private static UserMapper userMapper() {
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        return userMapper;
    }
}
