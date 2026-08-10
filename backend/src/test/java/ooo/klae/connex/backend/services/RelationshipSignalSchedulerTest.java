package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class RelationshipSignalSchedulerTest {
    private static final int WORKSPACE_ID = 7;

    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private RelationshipSignalReconciliationService reconciliationService;
    @Mock private JobRunRecorder jobRunRecorder;

    private RelationshipSignalScheduler scheduler;
    private AtomicBoolean insideWorkspace;

    @BeforeEach
    void setUp() {
        scheduler = new RelationshipSignalScheduler(
            workspaceMapper, tenantWorkScope, reconciliationService, jobRunRecorder);
        insideWorkspace = new AtomicBoolean();
        when(workspaceMapper.findWorkspaceIds()).thenReturn(List.of(WORKSPACE_ID));
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<List<Integer>>>any()))
            .thenAnswer(invocation -> {
                Supplier<List<Integer>> work = invocation.getArgument(0);
                return work.get();
            });
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            insideWorkspace.set(true);
            try {
                work.run();
            } finally {
                insideWorkspace.set(false);
            }
            return null;
        }).when(tenantWorkScope).inWorkspace(eq(WORKSPACE_ID), any(Runnable.class));
        doAnswer(invocation -> {
            assertTrue(insideWorkspace.get(), "job outcomes must be recorded inside tenant routing");
            return null;
        }).when(jobRunRecorder).record(
            eq(JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION),
            eq(WORKSPACE_ID),
            any(JobRunStatus.class),
            any(JobRunDetail.class));
    }

    @Test
    void recordsFailureBeforeAReconciliationExceptionLeavesTheWorkspaceScope() {
        when(reconciliationService.reconcileWorkspace(WORKSPACE_ID))
            .thenThrow(new IllegalStateException("failed"));

        scheduler.reconcile();

        ArgumentCaptor<JobRunDetail> detail = ArgumentCaptor.forClass(JobRunDetail.class);
        verify(jobRunRecorder).record(
            eq(JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION),
            eq(WORKSPACE_ID),
            eq(JobRunStatus.FAILED),
            detail.capture());
        assertEquals("workspace_reconciliation", detail.getValue().metadata().get("phase"));
    }

    @Test
    void recordsFailedOutcomeWhenAReconciliationFamilyFails() {
        when(reconciliationService.reconcileWorkspace(WORKSPACE_ID))
            .thenReturn(new RelationshipSignalReconciliationService.Result(
                Set.of(
                    RelationshipSignalDetectorService.RELATIONSHIP_DECAY,
                    RelationshipSignalDetectorService.DEAL_RISK,
                    RelationshipSignalDetectorService.WARM_PATH),
                2));

        scheduler.reconcile();

        ArgumentCaptor<JobRunDetail> detail = ArgumentCaptor.forClass(JobRunDetail.class);
        verify(jobRunRecorder).record(
            eq(JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION),
            eq(WORKSPACE_ID),
            eq(JobRunStatus.FAILED),
            detail.capture());
        assertEquals(2, detail.getValue().metadata().get("failedCount"));
    }
}
