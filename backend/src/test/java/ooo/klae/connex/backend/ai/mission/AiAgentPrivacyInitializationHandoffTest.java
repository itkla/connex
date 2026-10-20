package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/** Proves routed callback handoff, failed-worker recovery and synchronous default-mode parity. */
class AiAgentPrivacyInitializationHandoffTest {
    private final TenantContext context = new TenantContext();
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final TenantWorkspaceScopeControlOperations operations = mock(TenantWorkspaceScopeControlOperations.class);
    private final TenantWorkScope scope = new TenantWorkScope(context, catalogs, workspaces,
        mock(TenantLifecycleControlMapper.class), operations);
    private final AiAgentPrivacyService privacy = mock(AiAgentPrivacyService.class);
    private final AiAgentStartupRecoveryService recovery = mock(AiAgentStartupRecoveryService.class);
    private final SystemActor system = mock(SystemActor.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    @AfterEach
    void clear() {
        context.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void routedAfterCommitReturnsWithItsConnectionRetainedAndFailedAsyncWorkIsRecovered() throws Exception {
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 900, "cnx_tenant", Optional.empty(),
            new WorkspaceScope(900, List.of(7), "[7]"));
        when(operations.forWorkspace(7, false, null)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return prepared;
        });
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of(7));
        when(workspaces.findWorkspaceIdsPage(7, 100)).thenReturn(List.of());
        User actor = new User();
        actor.setId(1);
        when(system.user()).thenReturn(actor);
        doThrow(new IllegalStateException("first initialization failed")).doNothing().when(privacy).initialize(7);
        var initializer = initializer();
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        context.set(3, 5, 9, "owner", null);

        new TransactionTemplate(transactions).executeWithoutResult(status ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                    initializer.provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));
                    assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                    assertEquals(1, pending.size());
                    assertEquals(3, context.getWorkspaceId());
                    verifyNoInteractions(operations, privacy);
                }
            }));
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));

        try (var worker = Executors.newSingleThreadExecutor()) {
            Runnable task = pending.remove();
            worker.submit(() -> {
                assertFalse(context.isResolved());
                assertNull(context.getWorkspaceScopes());
                task.run();
                assertFalse(context.isResolved());
                assertNull(context.getWorkspaceScopes());
                assertNull(context.getCatalog());
            }).get(5, TimeUnit.SECONDS);
        }
        assertFalse(initializer.isReady());
        assertEquals(3, context.getWorkspaceId());
        verify(privacy).initialize(7);
        verifyNoInteractions(recovery);

        context.clear();
        initializer.reconcile();

        assertTrue(initializer.isReady());
        assertTrue(pending.isEmpty());
        verify(privacy, times(2)).initialize(7);
        verify(privacy).reconcileLegacyTurns(7);
        verify(recovery).reconcile(7);
        verify(operations, times(2)).forWorkspace(7, false, null);
        assertFalse(context.isResolved());
        assertNull(context.getWorkspaceScopes());
    }

    @Test
    void singleDatabaseProvisioningStaysSynchronousAndDoesNotPrepareScopes() {
        when(workspaces.getOrgId(7)).thenReturn(900);
        User actor = new User();
        actor.setId(1);
        when(system.user()).thenReturn(actor);
        doAnswer(call -> {
            assertEquals(7, context.getWorkspaceId());
            assertNull(context.getWorkspaceScopes());
            return null;
        }).when(privacy).initialize(7);

        initializer().provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));

        verify(privacy).initialize(7);
        verify(recovery).reconcile(7);
        verifyNoInteractions(operations);
        assertTrue(pending.isEmpty());
        assertFalse(context.isResolved());
    }

    private AiAgentPrivacyInitializer initializer() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("applicationTaskExecutor", (TaskExecutor) pending::add);
        return new AiAgentPrivacyInitializer(workspaces, scope, privacy, recovery,
            mock(JobRunRecorder.class), new TestTransactionManager(),
            new AutomationExecutor(context, new AutomationScope(), scope), system,
            beans.getBeanProvider(TaskExecutor.class));
    }
}
