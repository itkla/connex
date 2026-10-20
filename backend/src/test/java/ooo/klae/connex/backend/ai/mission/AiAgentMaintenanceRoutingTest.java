package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWatermarks;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.seeder.SeederExitListener;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiAgentMaintenanceRoutingTest {
    private static final int WORKSPACE = 7;
    private static final int ORG = 11;
    private static final String CATALOG = "cnx_privacy";
    private final TenantContext context = new TenantContext();
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantLifecycleControlMapper lifecycle = mock(TenantLifecycleControlMapper.class);
    private final TenantWorkScope scope = new TenantWorkScope(context, catalogs, workspaces, lifecycle);

    private void placement() {
        when(workspaces.getOrgId(WORKSPACE)).thenReturn(ORG);
        when(lifecycle.findWorkspaceOrgIdForLifecycle(WORKSPACE)).thenReturn(ORG);
        when(catalogs.resolveCatalog(ORG)).thenReturn(CATALOG);
    }

    @Test
    void invalidationKeepsTheCatalogInstalledBeforeItsTransaction() {
        placement();
        var privacy = mock(AiAgentPrivacyMapper.class);
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(WORKSPACE);
        barrier.setGeneration(1);
        when(privacy.lockBarrier(WORKSPACE)).thenReturn(barrier);
        when(privacy.watermarks(WORKSPACE)).thenReturn(new AiAgentPrivacyWatermarks());
        when(privacy.advanceBarrier(eq(WORKSPACE), eq(1L), any())).thenReturn(1);
        var service = new AiAgentPrivacyService(privacy, mock(AiAgentRunMapper.class), mock(ooo.klae.connex.backend.mappers.AiAgentActionMapper.class), mock(AiChatMapper.class),
                mock(AiRestrictionEpoch.class), mock(AiRunLeaseService.class), mock(PlatformTransactionManager.class),
                workspaces, lifecycle, mock(UserMapper.class), scope, catalogs, context, Clock.systemUTC());
        CompletableFuture<Boolean> placementBeforeTransaction = new CompletableFuture<>();
        when(catalogs.resolveCatalog(ORG)).thenAnswer(call -> {
            placementBeforeTransaction.complete(!TransactionSynchronizationManager.isActualTransactionActive());
            return CATALOG;
        });
        scope.inWorkspace(WORKSPACE, () -> {
            assertEquals(CATALOG, context.getCatalog());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                service.invalidateWorkspaces(List.of(WORKSPACE), UUID.randomUUID(), "restriction", null, null);
                assertEquals(CATALOG, context.getCatalog());
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            return null;
        });
        assertTrue(placementBeforeTransaction.join());
        verify(privacy).insertWork(any());
        assertNull(context.getCatalog());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void privacyReadinessCompletesBeforeTheSeederClosesTheReadyContext() {
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of());
        var initializer = initializer(mock(AiAgentPrivacyService.class), mock(JobRunRecorder.class));
        try (var application = new AnnotationConfigApplicationContext()) {
            application.addApplicationListener(new SeederExitListener(application));
            application.registerBean(AiAgentPrivacyInitializer.class, () -> initializer);
            application.refresh();

            application.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                    application, Duration.ZERO));

            assertTrue(initializer.isReady());
            assertFalse(application.isActive());
        }
    }

    @ParameterizedTest
    @CsvSource({"scheduler,false", "scheduler,true", "initializer,false", "initializer,true", "cleanup,false", "cleanup,true"})
    void maintenanceRecordsSuccessAndFailureInsideDedicatedPlacement(String job, boolean fails) throws Exception {
        placement();
        when(workspaces.findWorkspaceIdsPage(0, 100)).thenReturn(List.of(WORKSPACE));
        when(workspaces.findWorkspaceIdsLifecyclePage(0, 20)).thenReturn(List.of(WORKSPACE));
        var privacy = mock(AiAgentPrivacyService.class);
        var runs = mock(AiAgentRunMapper.class);
        var recorder = mock(JobRunRecorder.class);
        var observed = new CompletableFuture<Recorded>();
        doAnswer(call -> {
            observed.complete(new Recorded(call.getArgument(0), context.getCatalog(), call.getArgument(2)));
            return null;
        }).when(recorder).record(any(), eq("cleanup".equals(job) ? null : WORKSPACE), any(), any());
        if (fails) {
            doThrow(new IllegalStateException("fixture")).when(privacy).initialize(WORKSPACE);
            doThrow(new IllegalStateException("fixture")).when(privacy).reconcileErasedOwners(WORKSPACE);
            when(runs.expiredRuns(WORKSPACE, 25)).thenThrow(new IllegalStateException("fixture"));
        }
        String expectedJob;
        switch (job) {
            case "initializer" -> {
                expectedJob = JobRunRecorder.AI_AGENT_PRIVACY_INITIALIZATION;
                initializer(privacy, recorder).reconcile();
            }
            case "cleanup" -> {
                expectedJob = JobRunRecorder.AI_AGENT_PRIVACY_CLEANUP;
                new AiAgentPrivacyWorker(workspaces, mock(AiAgentPrivacyMapper.class), privacy,
                        mock(AiAgentDepartureMaintenanceService.class), scope, recorder).sweep();
            }
            case "scheduler" -> {
                expectedJob = JobRunRecorder.AI_AGENT_SCHEDULER;
                var initializer = mock(AiAgentPrivacyInitializer.class);
                when(initializer.isReady()).thenReturn(true);
                var scheduler = new AiAgentScheduler(workspaces, scope, runs, mock(AiAgentSegmentRunner.class),
                        initializer, new AiProperties(), mock(AiAgentRunPersistenceService.class),
                        mock(AiAgentRealtimePublisher.class), recorder);
                try {
                    scheduler.sweep();
                    observed.get(10, TimeUnit.SECONDS);
                } finally {
                    scheduler.shutdown();
                }
            }
            default -> throw new AssertionError(job);
        }
        assertEquals(new Recorded(expectedJob, "cleanup".equals(job) ? null : CATALOG, fails ? JobRunStatus.FAILED : JobRunStatus.SUCCEEDED),
                observed.get(10, TimeUnit.SECONDS));
        assertNull(context.getCatalog());
    }

    private AiAgentPrivacyInitializer initializer(AiAgentPrivacyService privacy, JobRunRecorder recorder) {
        var system = mock(SystemActor.class);
        var user = new User();
        user.setId(1);
        when(system.user()).thenReturn(user);
        return new AiAgentPrivacyInitializer(workspaces, scope, privacy,
                mock(AiAgentStartupRecoveryService.class), recorder, mock(PlatformTransactionManager.class),
                new AutomationExecutor(context, new AutomationScope(), scope), system);
    }

    private record Recorded(String job, String catalog, JobRunStatus status) {}
}
