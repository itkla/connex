package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyInitializer;
import ooo.klae.connex.backend.ai.mission.AiAgentPrivacyService;
import ooo.klae.connex.backend.ai.mission.AiAgentStartupRecoveryService;
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

/** Exercises the real managed executor alongside another production Executor bean, without a database. */
@ExtendWith(OutputCaptureExtension.class)
class AiAgentPrivacyInitializationConfigurationTest {
    private final TenantContext tenantContext = new TenantContext();
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final TenantWorkspaceScopeControlOperations operations = mock(TenantWorkspaceScopeControlOperations.class);
    private final TenantWorkScope scope = new TenantWorkScope(tenantContext, catalogs, workspaces,
        mock(TenantLifecycleControlMapper.class), operations);
    private final AiAgentPrivacyService privacy = mock(AiAgentPrivacyService.class);
    private final AiAgentStartupRecoveryService recovery = mock(AiAgentStartupRecoveryService.class);
    private final SystemActor system = mock(SystemActor.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AiAgentPrivacyInitializationConfiguration.class,
            AiIdentifierKeyBackfillSchedulingConfig.class, AiAgentPrivacyInitializer.class)
        .withBean(WorkspaceMapper.class, () -> workspaces)
        .withBean(TenantWorkScope.class, () -> scope)
        .withBean(AiAgentPrivacyService.class, () -> privacy)
        .withBean(AiAgentStartupRecoveryService.class, () -> recovery)
        .withBean(JobRunRecorder.class, () -> mock(JobRunRecorder.class))
        .withBean(TestTransactionManager.class, TestTransactionManager::new)
        .withBean(AutomationExecutor.class, () -> new AutomationExecutor(tenantContext, new AutomationScope(), scope))
        .withBean(SystemActor.class, () -> system);

    @BeforeEach
    void routedWorkspace() {
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        when(operations.forWorkspace(7, false, null)).thenReturn(new TenantWorkspaceScopeSnapshot(
            7, 900, "cnx_tenant", Optional.empty(), new WorkspaceScope(900, List.of(7), "[7]")));
        User actor = new User();
        actor.setId(1);
        when(system.user()).thenReturn(actor);
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "seeder"})
    void productionGraphHandsOffWithNoDefaultExecutorAndClosesItsWorker(String maintenanceMode) {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<ThreadPoolTaskExecutor> managed = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        doAnswer(call -> {
            worker.set(Thread.currentThread());
            assertEquals(7, tenantContext.getWorkspaceId());
            assertTrue(tenantContext.getWorkspaceScopes() != null);
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(privacy).initialize(7);

        runner.withPropertyValues("connex.maintenance.mode=" + maintenanceMode).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("applicationTaskExecutor"));
            assertEquals("off".equals(maintenanceMode), context.containsBean("aiIdentifierKeyBackfillScheduler"));
            var executor = context.getBean("aiAgentPrivacyInitializationExecutor", ThreadPoolTaskExecutor.class);
            managed.set(executor);
            tenantContext.set(3, 5, 9, "owner", null);
            try {
                context.getBean(AiAgentPrivacyInitializer.class)
                    .provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertNotSame(caller, worker.get());
                assertTrue(worker.get().getName().startsWith("ai-privacy-initialization-"));
                assertEquals(3, tenantContext.getWorkspaceId());
            } finally {
                release.countDown();
            }
            executor.submit(() -> {
                assertFalse(tenantContext.isResolved());
                assertNull(tenantContext.getWorkspaceScopes());
                assertNull(SecurityContextHolder.getContext().getAuthentication());
            }).get(5, TimeUnit.SECONDS);
            verify(privacy).initialize(7);
            verify(recovery).reconcile(7);
        });
        assertTrue(managed.get().getThreadPoolExecutor().isTerminated());
    }

    @Test
    void saturationLogsRefusalClosesReadinessAndLeavesInitializationForScheduledRecovery(CapturedOutput output) {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var initializer = context.getBean(AiAgentPrivacyInitializer.class);
            var executor = context.getBean("aiAgentPrivacyInitializationExecutor", ThreadPoolTaskExecutor.class);
            initializer.reconcile();
            assertTrue(initializer.isReady());
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch drained = new CountDownLatch(100);
            executor.execute(() -> {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int queued = 0; queued < 100; queued++) {
                    executor.execute(drained::countDown);
                }
                assertEquals(0, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
                initializer.provisioned(new AiAgentPrivacyInitializer.WorkspaceProvisioned(7));
                assertFalse(initializer.isReady());
                assertTrue(output.getOut().contains("AI privacy initialization handoff failed for workspace 7"));
                verifyNoInteractions(privacy, operations);
            } finally {
                release.countDown();
            }
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of(7));
            when(workspaces.findWorkspaceIdsPage(7, 100)).thenReturn(List.of());

            initializer.reconcile();

            assertTrue(initializer.isReady());
            verify(privacy).initialize(7);
            verify(recovery).reconcile(7);
            assertFalse(tenantContext.isResolved());
            assertNull(tenantContext.getWorkspaceScopes());
        });
    }
}
