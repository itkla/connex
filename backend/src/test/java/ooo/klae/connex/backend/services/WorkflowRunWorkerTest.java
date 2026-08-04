package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.services.WorkflowWorkClaim.Kind;

@ExtendWith(MockitoExtension.class)
class WorkflowRunWorkerTest {

    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowDelayResumeService delayResumeService;
    @Mock private WorkflowTraversalService traversalService;
    @Mock private WorkflowRunCancellationService cancellationService;
    @Mock private WorkflowRunFailureService failureService;
    @Mock private WorkflowRuntimeProperties properties;

    @Test
    void cancellationCommittedBeforeDelayLockIsFinalizedWithoutLeaseExpiry() {
        WorkflowRunWorker worker = new WorkflowRunWorker(
            runMapper,
            delayResumeService,
            traversalService,
            cancellationService,
            failureService,
            properties);
        WorkflowWorkClaim claim = new WorkflowWorkClaim(
            Kind.RUN, 7, 31L, "owner", "delay");
        WorkflowRun run = new WorkflowRun();
        run.setCurrentNodeId("delay");
        when(runMapper.getByIdInWorkspace(7, 31L)).thenReturn(run);
        when(cancellationService.finalizeClaimed(7, 31L, "owner"))
            .thenReturn(false, true);
        when(delayResumeService.resume(7, 31L, "owner")).thenReturn(false);

        worker.process(claim);

        verify(cancellationService, org.mockito.Mockito.times(2))
            .finalizeClaimed(7, 31L, "owner");
        verify(traversalService, never()).resumeClaimed(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void ambiguousDelayCommitIsFencedToThePreResumeNode() {
        WorkflowRunWorker worker = new WorkflowRunWorker(
            runMapper,
            delayResumeService,
            traversalService,
            cancellationService,
            failureService,
            properties);
        WorkflowWorkClaim claim = new WorkflowWorkClaim(
            Kind.RUN, 7, 31L, "owner", "delay");
        WorkflowRun run = new WorkflowRun();
        run.setCurrentNodeId("delay");
        when(runMapper.getByIdInWorkspace(7, 31L)).thenReturn(run);
        when(delayResumeService.resume(7, 31L, "owner"))
            .thenThrow(new IllegalStateException("ambiguous commit"));

        worker.process(claim);

        verify(failureService).failClaimed(
            eq(7),
            eq(31L),
            eq("delay"),
            eq("owner"),
            eq(NodeType.DELAY),
            isA(IllegalStateException.class));
    }
}
