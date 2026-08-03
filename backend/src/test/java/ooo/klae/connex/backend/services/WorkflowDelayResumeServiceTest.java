package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

@ExtendWith(MockitoExtension.class)
class WorkflowDelayResumeServiceTest {

    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private WorkflowTraversalService traversalService;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private WorkflowRecordGuard recordGuard;

    @Test
    void dueResumeCompletesTheExistingDelayStepWithoutInsertingAnother() {
        WorkflowDelayResumeService service = new WorkflowDelayResumeService(
            runMapper,
            versionMapper,
            traversalService,
            principalService,
            recordGuard);
        WorkflowRun run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setCurrentNodeId("delay");
        run.setActorUserId(17);
        run.setAttributionUserId(17);
        when(runMapper.getOwnedByIdForUpdate(7, 31L, "owner")).thenReturn(run);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        when(versionMapper.getById(7, 11, 19L)).thenReturn(version);
        User actor = new User();
        actor.setId(17);
        when(principalService.resolve(7, version)).thenReturn(
            new WorkflowExecutionPrincipal(actor, "member", 17, 17));
        WorkflowNode.Delay delay = new WorkflowNode.Delay(
            "delay", new WorkflowDelayConfig(3_600));
        WorkflowEdge edge = new WorkflowEdge(
            "delay-end", "delay", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow compiled = new CompiledWorkflow(
            "trigger",
            Map.of("delay", delay),
            Map.of("delay", NodeType.DELAY),
            Map.of("delay", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            List.of("delay"),
            null);
        when(traversalService.compiled(run)).thenReturn(compiled);
        when(runMapper.succeedWaitingDelayStep(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(31L),
            org.mockito.ArgumentMatchers.eq("delay"),
            org.mockito.ArgumentMatchers.eq("delay-end"),
            org.mockito.ArgumentMatchers.eq("end"),
            any())).thenReturn(1);
        when(runMapper.advanceClaimedRun(
            7, 31L, "delay", "end", "owner")).thenReturn(1);

        assertTrue(service.resume(7, 31L, "owner"));

        verify(recordGuard).requireAccessible(run);
        verify(runMapper, never()).insertStep(any());
        verify(runMapper).advanceClaimedRun(7, 31L, "delay", "end", "owner");
    }
}
