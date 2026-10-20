package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
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
import org.springframework.transaction.PlatformTransactionManager;
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
    private final ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService assistantPrivacy =
        mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class);
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
    void singleDatabaseProvisioningPreparesAfterCommitAndInitializesSynchronously() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 900, null, Optional.empty(),
            new WorkspaceScope(900, List.of(7), "[7]"));
        when(workspaces.getOrgId(7)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            return 900;
        });
        when(operations.load(7, 900, null, null)).thenAnswer(call -> {
            verify(connection).commit();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            return prepared;
        });
        User actor = new User();
        actor.setId(1);
        when(system.user()).thenReturn(actor);
        doAnswer(call -> {
            assertEquals(7, context.getWorkspaceId());
            assertSame(prepared, context.getWorkspaceScopes());
            return null;
        }).when(privacy).initialize(7);

        var initializer = initializer(transactions);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            verifyNoInteractions(operations, privacy);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Object retained = TransactionSynchronizationManager.getResource(dataSource);
                    assertTrue(retained != null);
                    initializer.provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));
                    assertSame(retained, TransactionSynchronizationManager.getResource(dataSource));
                    verify(recovery).reconcile(7);
                    assertTrue(pending.isEmpty());
                }
            });
        });

        verify(privacy).initialize(7);
        verify(recovery).reconcile(7);
        verify(operations, times(1)).load(7, 900, null, null);
        verify(workspaces, times(1)).getOrgId(7);
        var order = inOrder(connection, operations, privacy, recovery);
        order.verify(connection).commit();
        order.verify(operations).load(7, 900, null, null);
        order.verify(privacy).initialize(7);
        order.verify(privacy).reconcileLegacyTurns(7);
        order.verify(recovery).reconcile(7);
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
        assertTrue(pending.isEmpty());
        assertFalse(context.isResolved());
    }

    @Test
    void reconciliationCompletesOrganizationWorkEvenWhenNoWorkspaceSurvives() {
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of());
        when(assistantPrivacy.incompleteOrganizationIdsPage(0, 100)).thenReturn(List.of(900));
        when(assistantPrivacy.incompleteOrganizationIdsPage(900, 100)).thenReturn(List.of());
        var initializer = initializer();

        initializer.reconcile();

        verify(assistantPrivacy).reconcileOrganizationCompletion(900);
        verifyNoInteractions(privacy, recovery);
        assertTrue(initializer.isReady());
    }

    @Test
    void organizationReconciliationTraversesPagesPastEarlierPendingOrganizations() {
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of());
        var earlier = java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList();
        when(assistantPrivacy.incompleteOrganizationIdsPage(0, 100)).thenReturn(earlier);
        when(assistantPrivacy.incompleteOrganizationIdsPage(100, 100)).thenReturn(List.of(900));
        when(assistantPrivacy.incompleteOrganizationIdsPage(900, 100)).thenReturn(List.of());
        var initializer = initializer();

        initializer.reconcile();

        for (int organizationId : earlier) {
            verify(assistantPrivacy).reconcileOrganizationCompletion(organizationId);
        }
        verify(assistantPrivacy).reconcileOrganizationCompletion(900);
        verifyNoInteractions(privacy, recovery);
        assertTrue(initializer.isReady());
    }

    @Test
    void organizationCompletionFailureKeepsReadinessClosedAndIsRetriedWithoutWorkspaceRoots() {
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of());
        when(assistantPrivacy.incompleteOrganizationIdsPage(0, 100)).thenReturn(List.of(900));
        when(assistantPrivacy.incompleteOrganizationIdsPage(900, 100)).thenReturn(List.of());
        doThrow(new IllegalStateException("completion interrupted")).doNothing()
            .when(assistantPrivacy).reconcileOrganizationCompletion(900);
        var initializer = initializer();

        initializer.reconcile();
        assertFalse(initializer.isReady());
        initializer.reconcile();

        assertTrue(initializer.isReady());
        verify(assistantPrivacy, times(2)).reconcileOrganizationCompletion(900);
    }

    private AiAgentPrivacyInitializer initializer() {
        return initializer(new TestTransactionManager());
    }

    private AiAgentPrivacyInitializer initializer(PlatformTransactionManager transactions) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("aiAgentPrivacyInitializationExecutor", (TaskExecutor) pending::add);
        return new AiAgentPrivacyInitializer(workspaces, scope, privacy, recovery, assistantPrivacy,
            mock(JobRunRecorder.class), transactions,
            new AutomationExecutor(context, new AutomationScope(), scope), system,
            beans.getBeanProvider(TaskExecutor.class));
    }
}
