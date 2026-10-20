package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyInitializer;
import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService;
import ooo.klae.connex.backend.ai.mission.AiAgentStartupRecoveryService;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.SystemActor;

/** Reproduces the request-thread guard and after-commit resource lifetime without a database. */
class AiAgentPrivacyProvisioningScopeTest {
    private final TenantContext context = new TenantContext();

    @AfterEach
    void clear() {
        context.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,false,true", "false,true,true",
            "true,false,true", "true,true,true"})
    void committedProvisioningSuspendsControlResourcesAndInstallsRestorableScope(
            boolean dedicated, boolean existingScope, boolean committedCallback) throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        var workspaces = mock(WorkspaceMapper.class);
        var catalogs = mock(TenantCatalogResolver.class);
        var scope = new TenantWorkScope(context, catalogs, workspaces);
        String catalog = dedicated ? "cnx_new_workspace" : null;
        when(workspaces.getOrgId(7)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
            return 11;
        });
        when(catalogs.resolveCatalog(11)).thenReturn(catalog);
        var system = mock(SystemActor.class);
        var principal = new User();
        principal.setId(1);
        when(system.user()).thenReturn(principal);
        var privacy = mock(AiAgentPrivacyService.class);
        var recovery = mock(AiAgentStartupRecoveryService.class);
        var recorder = mock(JobRunRecorder.class);
        var initializer = new AiAgentPrivacyInitializer(workspaces, scope, privacy, recovery, recorder,
                transactions, new AutomationExecutor(context, new AutomationScope(), scope), system,
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(org.springframework.core.task.TaskExecutor.class));
        var request = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(request);
        var security = SecurityContextHolder.getContext();
        if (existingScope) {
            context.set(3, 5, 9, "owner", null);
        }
        var guard = new TenantScopeInterceptor(context, true, dedicated);
        doAnswer(call -> {
            guard.enforce("ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper.lockBarrier");
            assertEquals(7, context.getWorkspaceId());
            assertEquals(11, context.getOrgId());
            assertEquals(catalog, context.getCatalog());
            assertEquals("system", context.getRole());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(privacy).initialize(7);

        if (committedCallback) {
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                        initializer.provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));
                        assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                    }
                }));
        } else {
            initializer.initialize(7);
        }

        verify(privacy).initialize(7);
        verify(privacy).reconcileLegacyTurns(7);
        verify(recovery).reconcile(7);
        verify(recorder).record(eq(JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION), eq(7),
                eq(JobRunStatus.SUCCEEDED), any());
        assertSame(request, RequestContextHolder.getRequestAttributes());
        assertSame(security, SecurityContextHolder.getContext());
        assertEquals(existingScope, context.isResolved());
        if (existingScope) {
            assertEquals(3, context.getWorkspaceId());
            assertEquals(9, context.getUserId());
        }
        assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
    }
}
