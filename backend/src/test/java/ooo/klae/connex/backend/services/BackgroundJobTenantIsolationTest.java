package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.CampaignSendWorker;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies off-request fan-out preserves each enumerated workspace at every downstream call. */
class BackgroundJobTenantIsolationTest {

    private static final int SIBLING_WORKSPACE_ID = 11;
    private static final int FOREIGN_ORGANIZATION_WORKSPACE_ID = 22;
    private static final String FOREIGN_CATALOG = "cnx_foreign";

    @Test
    void ruleSchedulerDispatchesEveryCadenceWithTheEnumeratedWorkspace() {
        RuleMapper ruleMapper = mock(RuleMapper.class);
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        PlacementRegistry placementRegistry = mock(PlacementRegistry.class);
        RecordingTenantWorkScope tenantWorkScope = new RecordingTenantWorkScope();
        WorkflowTriggerIntake workflowTriggerIntake = mock(WorkflowTriggerIntake.class);
        WorkflowRuntimeProperties properties = mock(WorkflowRuntimeProperties.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        JobRunRecorder jobRunRecorder = mock(JobRunRecorder.class);
        RuleScheduler scheduler = new RuleScheduler(
                ruleMapper,
                workflowMapper,
                placementRegistry,
                tenantWorkScope,
                workflowTriggerIntake,
                properties,
                ruleEngineService,
                jobRunRecorder);
        ReflectionTestUtils.setField(scheduler, "schedulingEnabled", true);
        when(placementRegistry.activeCatalogs())
                .thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(ruleMapper.workspaceIdsWithEnabledScheduleRules())
                .thenReturn(List.of(SIBLING_WORKSPACE_ID))
                .thenReturn(List.of(FOREIGN_ORGANIZATION_WORKSPACE_ID));
        when(workflowMapper.workspaceIdsWithEnabledScheduleWorkflows())
                .thenReturn(List.of());
        when(properties.enabled()).thenReturn(false);

        scheduler.evaluate();

        ArgumentCaptor<WorkflowTriggerDispatch> dispatches =
                ArgumentCaptor.forClass(WorkflowTriggerDispatch.class);
        verify(workflowTriggerIntake, times(6)).enqueue(dispatches.capture());
        assertWorkspaceDispatches(dispatches.getAllValues(), SIBLING_WORKSPACE_ID);
        assertWorkspaceDispatches(
                dispatches.getAllValues(), FOREIGN_ORGANIZATION_WORKSPACE_ID);
        assertEquals(
                List.of(SIBLING_WORKSPACE_ID, FOREIGN_ORGANIZATION_WORKSPACE_ID),
                tenantWorkScope.workspaceIds);
        assertEquals(
                Arrays.asList(null, FOREIGN_CATALOG),
                tenantWorkScope.catalogs);
    }

    @Test
    void campaignWorkerPassesEachEnumeratedWorkspaceToItsDispatcher() {
        PlacementRegistry placementRegistry = mock(PlacementRegistry.class);
        RecordingTenantWorkScope tenantWorkScope = new RecordingTenantWorkScope();
        CampaignSendMapper mapper = mock(CampaignSendMapper.class);
        CampaignDispatchService dispatchService = mock(CampaignDispatchService.class);
        JobRunRecorder jobRunRecorder = mock(JobRunRecorder.class);
        CampaignSendWorker worker = new CampaignSendWorker(
                placementRegistry,
                tenantWorkScope,
                mapper,
                dispatchService,
                jobRunRecorder);
        ReflectionTestUtils.setField(worker, "dispatchEnabled", true);
        when(placementRegistry.activeCatalogs())
                .thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(mapper.workspaceIdsWithQueuedSends())
                .thenReturn(List.of(SIBLING_WORKSPACE_ID))
                .thenReturn(List.of(FOREIGN_ORGANIZATION_WORKSPACE_ID));

        worker.dispatch();

        verify(dispatchService).processWorkspace(SIBLING_WORKSPACE_ID);
        verify(dispatchService).processWorkspace(FOREIGN_ORGANIZATION_WORKSPACE_ID);
        assertEquals(
                List.of(SIBLING_WORKSPACE_ID, FOREIGN_ORGANIZATION_WORKSPACE_ID),
                tenantWorkScope.workspaceIds);
        assertEquals(
                Arrays.asList(null, FOREIGN_CATALOG),
                tenantWorkScope.catalogs);
    }

    @Test
    void workflowRuntimeUsesTheEnumeratedWorkspaceForRecoveryClaimsAndRetention() {
        WorkflowTriggerOutboxMapper outboxMapper = mock(WorkflowTriggerOutboxMapper.class);
        WorkflowRuntimeClaimTransaction claimTransaction =
                mock(WorkflowRuntimeClaimTransaction.class);
        WorkflowTriggerOutboxWorker outboxWorker = mock(WorkflowTriggerOutboxWorker.class);
        WorkflowRunWorker runWorker = mock(WorkflowRunWorker.class);
        WorkflowManualRunRecoveryService recoveryService =
                mock(WorkflowManualRunRecoveryService.class);
        WorkflowRuntimeRetentionService retentionService =
                mock(WorkflowRuntimeRetentionService.class);
        WorkflowRuntimeProperties properties = mock(WorkflowRuntimeProperties.class);
        PlacementRegistry placementRegistry = mock(PlacementRegistry.class);
        RecordingTenantWorkScope tenantWorkScope = new RecordingTenantWorkScope();
        JobRunRecorder jobRunRecorder = mock(JobRunRecorder.class);
        WorkflowRuntimeScheduler scheduler = new WorkflowRuntimeScheduler(
                outboxMapper,
                claimTransaction,
                outboxWorker,
                runWorker,
                recoveryService,
                retentionService,
                properties,
                placementRegistry,
                tenantWorkScope,
                jobRunRecorder);
        when(placementRegistry.activeCatalogs())
                .thenReturn(Arrays.asList(null, FOREIGN_CATALOG));
        when(properties.maxGlobalWorkers()).thenReturn(4);
        when(properties.maxWorkspacesPerSweep()).thenReturn(4);
        when(properties.workspaceQuantum()).thenReturn(1);
        when(outboxMapper.workspaceIdsPage(0, 4))
                .thenReturn(List.of(SIBLING_WORKSPACE_ID))
                .thenReturn(List.of(FOREIGN_ORGANIZATION_WORKSPACE_ID));

        scheduler.sweep();

        verify(recoveryService).dispatchPending(SIBLING_WORKSPACE_ID, 1);
        verify(recoveryService).dispatchPending(FOREIGN_ORGANIZATION_WORKSPACE_ID, 1);
        verify(claimTransaction).claimNext(SIBLING_WORKSPACE_ID);
        verify(claimTransaction).claimNext(FOREIGN_ORGANIZATION_WORKSPACE_ID);
        verify(retentionService).purge(SIBLING_WORKSPACE_ID);
        verify(retentionService).purge(FOREIGN_ORGANIZATION_WORKSPACE_ID);
        assertEquals(
                List.of(SIBLING_WORKSPACE_ID, FOREIGN_ORGANIZATION_WORKSPACE_ID),
                tenantWorkScope.workspaceIds);
        assertEquals(
                Arrays.asList(null, FOREIGN_CATALOG),
                tenantWorkScope.catalogs);
    }

    private static final class RecordingTenantWorkScope extends TenantWorkScope {
        private final List<String> catalogs = new ArrayList<>();
        private final List<Integer> workspaceIds = new ArrayList<>();

        private RecordingTenantWorkScope() {
            super(
                    new TenantContext(),
                    mock(TenantCatalogResolver.class),
                    mock(WorkspaceMapper.class));
        }

        @Override
        public <T> T withCatalog(String catalog, Supplier<T> work) {
            catalogs.add(catalog);
            return work.get();
        }

        @Override
        public void inWorkspace(int workspaceId, Runnable work) {
            workspaceIds.add(workspaceId);
            work.run();
        }

        @Override
        public <T> T inWorkspace(int workspaceId, Supplier<T> work) {
            workspaceIds.add(workspaceId);
            return work.get();
        }
    }

    private static void assertWorkspaceDispatches(
            List<WorkflowTriggerDispatch> dispatches,
            int workspaceId) {
        List<WorkflowTriggerDispatch.ScheduleTick> matching = dispatches.stream()
                .filter(WorkflowTriggerDispatch.ScheduleTick.class::isInstance)
                .map(WorkflowTriggerDispatch.ScheduleTick.class::cast)
                .filter(dispatch -> dispatch.workspaceId() == workspaceId)
                .toList();
        assertEquals(3, matching.size());
        assertTrue(matching.stream().map(WorkflowTriggerDispatch.ScheduleTick::cadence)
                .toList().containsAll(List.of("hourly", "daily", "weekly")));
    }
}
