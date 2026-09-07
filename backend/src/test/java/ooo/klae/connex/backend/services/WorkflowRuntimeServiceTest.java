package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
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

    @Test
    void triggeredScheduleUsesCeilingPlusOneAndStrictlyAuditsTruncation() {
        WorkflowTriggerDispatch.ScheduleTick schedule =
            new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "20260903");
        when(properties.enabled()).thenReturn(true);
        when(workflowMapper.getEnabledCanonicalIdsByTrigger(7, "schedule"))
            .thenReturn(List.of(11));
        WorkflowVersion version = new WorkflowVersion();
        version.setId(23L);
        version.setRecordType("person");
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition("condition", definition);
        RuleAction action = new RuleAction();
        action.setType("send_message");
        WorkflowDefinitionValidator.CompiledWorkflow compiled =
            mock(WorkflowDefinitionValidator.CompiledWorkflow.class);
        when(compiled.nodes()).thenReturn(Map.of(
            "send", new WorkflowNode.Action("send", action)));
        when(claimService.scheduleEnrollment(11, schedule)).thenReturn(
            new WorkflowRuntimeClaimService.ScheduleEnrollment(
                11, version, compiled, condition, 17));
        List<Integer> matches = IntStream.rangeClosed(1, 201).boxed().toList();
        when(segmentService.evaluate(7, 17, "person", definition, 201))
            .thenReturn(matches);
        for (int recordId = 1; recordId <= 200; recordId++) {
            when(claimService.claimScheduleRecord(11, 23L, schedule, recordId))
                .thenReturn(WorkflowRuntimeClaimService.CanonicalClaim.rejectedClaim());
        }

        service.dispatch(schedule);

        verify(segmentService).evaluate(7, 17, "person", definition, 201);
        verify(auditService).recordStrict(
            "workflow.triggered_send.recipient_limit",
            "workflow",
            11,
            "Workflow 11",
            "Scheduled send-message recipients were capped",
            Map.of("limit", 200, "code", "triggered_send_recipient_limit"));
        verify(claimService, never()).claimScheduleRecord(11, 23L, schedule, 201);
    }
}
