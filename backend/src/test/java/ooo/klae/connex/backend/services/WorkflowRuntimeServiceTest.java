package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeServiceTest {

    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowRuntimeClaimService claimService;
    @Mock private WorkflowTraversalService traversalService;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private SegmentService segmentService;
    @Mock private RuleEngineService ruleEngineService;
    @Mock private WorkflowTriggeredSendGate triggeredSendGate;
    @Mock private AuditService auditService;

    private WorkflowRuntimeService service;
    private WorkflowTriggerDispatch.EntityChange dispatch;

    @BeforeEach
    void setUp() {
        service = new WorkflowRuntimeService(
            properties,
            workflowMapper,
            workflowRunMapper,
            claimService,
            traversalService,
            principalService,
            segmentService,
            ruleEngineService,
            triggeredSendGate,
            auditService);
        lenient().when(triggeredSendGate.recipientLimit()).thenReturn(200);
        dispatch = new WorkflowTriggerDispatch.EntityChange(
            7,
            "company",
            41,
            "company.updated",
            "event-7",
            Instant.parse("2026-08-02T12:00:00Z"));
    }

    @Test
    void disabledCanonicalGateRunsTheLegacyOwnerExactlyOnce() {
        when(properties.enabled()).thenReturn(false);

        WorkflowDispatchResult result = service.dispatch(dispatch);

        assertEquals(WorkflowDispatchResult.empty(), result);
        verify(ruleEngineService).onEntityChange(dispatch);
        verify(workflowMapper, never()).getEnabledCanonicalIdsByTrigger(7, "entity_change");
    }

    @Test
    void enabledGateChecksLegacyOnBothSidesOfTheCanonicalClaim() {
        when(properties.enabled()).thenReturn(true);
        when(workflowMapper.getEnabledCanonicalIdsByTrigger(7, "entity_change"))
            .thenReturn(List.of(11));
        WorkflowRun run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setStatus("running");
        run.setCurrentNodeId("trigger");
        when(claimService.claimEntity(11, dispatch)).thenReturn(
            new WorkflowRuntimeClaimService.CanonicalClaim(run, true, false, false));

        WorkflowDispatchResult result = service.dispatch(dispatch);

        assertEquals(1, result.candidates());
        assertEquals(1, result.started());
        InOrder ownershipWindow = inOrder(
            ruleEngineService, workflowMapper, claimService, traversalService);
        ownershipWindow.verify(ruleEngineService).onEntityChange(dispatch);
        ownershipWindow.verify(workflowMapper).getEnabledCanonicalIdsByTrigger(
            7, "entity_change");
        ownershipWindow.verify(claimService).claimEntity(11, dispatch);
        ownershipWindow.verify(traversalService).resume(
            new WorkflowRunResumeCommand(7, 31L, "trigger"));
        ownershipWindow.verify(ruleEngineService).onEntityChange(dispatch);
    }
}
