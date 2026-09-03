package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerOutboxDeliveryServiceTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private SegmentMapper segmentMapper;
    @Mock private SegmentService segmentService;
    @Mock private WorkflowRuntimeClaimService claimService;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private RuleEngineService ruleEngineService;
    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkflowTriggeredSendGate triggeredSendGate;
    @Mock private AuditService auditService;

    private WorkflowTriggerOutboxDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerOutboxDeliveryService(
            outboxMapper,
            workflowMapper,
            versionMapper,
            ruleMapper,
            workspaceMapper,
            segmentMapper,
            segmentService,
            claimService,
            principalService,
            ruleEngineService,
            properties,
            triggeredSendGate,
            auditService);
        lenient().when(triggeredSendGate.recipientLimit()).thenReturn(200);
    }

    @Test
    void entityDeliveryBracketsCanonicalClaimWithBothPersistedOwnerChecks() {
        WorkflowTriggerOutbox outbox = entityOutbox();
        Workflow workflow = matchingWorkflow();
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease"))
            .thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowVersion version = new WorkflowVersion();
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        Rule rule = new Rule();
        rule.setExecutionMode("user");
        rule.setRunAsUserId(17);
        when(ruleMapper.getById(7, 29)).thenReturn(rule);
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(outboxMapper.complete(7, 31L, "lease")).thenReturn(1);

        WorkflowTriggerOutboxDeliveryService.DeliveryResult result =
            service.deliver(7, 31L, "lease");

        assertEquals(
            WorkflowTriggerOutboxDeliveryService.DeliveryResult.COMPLETED,
            result);
        InOrder order = inOrder(
            workflowMapper,
            versionMapper,
            ruleMapper,
            workspaceMapper,
            ruleEngineService,
            claimService,
            outboxMapper);
        order.verify(outboxMapper).ensureWorkspaceGate(7);
        order.verify(outboxMapper).getOwnedForUpdate(7, 31L, "lease");
        order.verify(workflowMapper).getById(7, 11);
        order.verify(versionMapper).getById(7, 11, 23L);
        order.verify(ruleMapper).getById(7, 29);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 17);
        order.verify(workflowMapper).getByIdForUpdate(7, 11);
        order.verify(ruleEngineService)
            .onEntityChangeForWorkflow(11, entityDispatch());
        order.verify(claimService).claimOutbox(outbox, 19);
        order.verify(ruleEngineService)
            .onEntityChangeForWorkflow(11, entityDispatch());
        order.verify(outboxMapper).complete(7, 31L, "lease");
        verifyNoInteractions(segmentMapper, segmentService, principalService);
    }

    @Test
    void successfulScheduleDeliveryResolvesEarlierDeadTriggerDiagnostics() {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setId(31L);
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(23L);
        outbox.setWorkflowRuntimeGeneration(5L);
        outbox.setTriggerType("schedule");
        outbox.setTriggerEvent("daily");
        outbox.setTriggerKey("20260803");
        outbox.setRecordType("company");
        outbox.setRecordScanAfterId(0);
        outbox.setRecordScanUpperId(0);
        Workflow workflow = matchingWorkflow();
        workflow.setLegacyRuleId(null);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(23L);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition(
            "enrollment", definition);
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(compiled.nodes()).thenReturn(java.util.Map.of());
        WorkflowRuntimeClaimService.ScheduleEnrollment enrollment =
            new WorkflowRuntimeClaimService.ScheduleEnrollment(
                11, version, compiled, condition, 17);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease"))
            .thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(claimService.outboxScheduleEnrollment(outbox)).thenReturn(enrollment);
        when(properties.maxScheduleRecordsPerPage()).thenReturn(100);
        when(segmentMapper.entityIdsPage(7, "company", 0, 0, 100))
            .thenReturn(List.of());
        when(outboxMapper.saveSchedulePage(7, 31L, "lease", 0, 0, true))
            .thenReturn(1);

        service.deliver(7, 31L, "lease");

        verify(outboxMapper).resolveDeadForWorkflow(7, 11);
    }

    @Test
    void unavailableScheduleActorFailsBeforeEnrollmentWithoutCompletingTheOutbox() {
        WorkflowTriggerOutbox outbox = scheduleOutbox();
        Workflow workflow = matchingWorkflow();
        workflow.setLegacyRuleId(null);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(23L);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition(
            "enrollment", definition);
        WorkflowRuntimeClaimService.ScheduleEnrollment enrollment =
            new WorkflowRuntimeClaimService.ScheduleEnrollment(
                11, version, mock(CompiledWorkflow.class), condition, 17);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease"))
            .thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(claimService.outboxScheduleEnrollment(outbox)).thenReturn(enrollment);
        when(principalService.resolve(7, version)).thenThrow(
            new WorkflowExecutionException(
                "actor_unavailable", "Actor unavailable", true));

        WorkflowExecutionException failure = assertThrows(
            WorkflowExecutionException.class,
            () -> service.deliver(7, 31L, "lease"));

        assertEquals("actor_unavailable", failure.code());
        verify(outboxMapper, never()).saveSchedulePage(
            anyInt(), anyLong(), anyString(), anyInt(), anyInt(), anyBoolean());
        verifyNoInteractions(segmentMapper, segmentService);
    }

    @Test
    void scheduledTriggeredSendStopsAtConfiguredRecipientLimitWithDiagnosticAndStrictAudit() {
        WorkflowTriggerOutbox outbox = scheduleOutbox();
        outbox.setRecordType("person");
        outbox.setRecordScanUpperId(2);
        Workflow workflow = matchingWorkflow();
        workflow.setLegacyRuleId(null);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(23L);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        SegmentDefinition definition = new SegmentDefinition();
        WorkflowNode.Condition condition = new WorkflowNode.Condition("enrollment", definition);
        RuleAction send = new RuleAction();
        send.setType("send_message");
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(compiled.nodes()).thenReturn(Map.of(
            "send", new WorkflowNode.Action("send", send)));
        WorkflowRuntimeClaimService.ScheduleEnrollment enrollment =
            new WorkflowRuntimeClaimService.ScheduleEnrollment(
                11, version, compiled, condition, 17);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease")).thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(claimService.outboxScheduleEnrollment(outbox)).thenReturn(enrollment);
        when(properties.maxScheduleRecordsPerPage()).thenReturn(100);
        when(triggeredSendGate.recipientLimit()).thenReturn(1);
        when(segmentMapper.entityIdsPage(7, "person", 0, 2, 100))
            .thenReturn(List.of(1, 2));
        when(segmentService.matchesEntity(7, 17, "person", definition, 1)).thenReturn(true);
        when(segmentService.matchesEntity(7, 17, "person", definition, 2)).thenReturn(true);
        when(outboxMapper.deadLetter(
            7, 31L, "lease", "triggered_send_recipient_limit")).thenReturn(1);

        service.deliver(7, 31L, "lease");

        verify(claimService).claimOutbox(outbox, 1);
        verify(claimService, never()).claimOutbox(outbox, 2);
        verify(outboxMapper).deadLetter(
            7, 31L, "lease", "triggered_send_recipient_limit");
        verify(auditService).recordStrict(
            "workflow.triggered_send.recipient_limit",
            "workflow",
            11,
            "Workflow 11",
            "Scheduled send-message recipients were capped",
            Map.of("limit", 1, "code", "triggered_send_recipient_limit"));
    }

    private static WorkflowTriggerOutbox scheduleOutbox() {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setId(31L);
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(23L);
        outbox.setWorkflowRuntimeGeneration(5L);
        outbox.setTriggerType("schedule");
        outbox.setTriggerEvent("daily");
        outbox.setTriggerKey("20260803");
        outbox.setRecordType("company");
        outbox.setRecordScanAfterId(0);
        outbox.setRecordScanUpperId(0);
        return outbox;
    }

    private static WorkflowTriggerOutbox entityOutbox() {
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setId(31L);
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(23L);
        outbox.setWorkflowRuntimeGeneration(5L);
        outbox.setTriggerType("entity_change");
        outbox.setTriggerEvent("deal.stage_changed");
        outbox.setTriggerKey("event-key");
        outbox.setRecordType("deal");
        outbox.setRecordId(19);
        outbox.setOccurredAt(LocalDateTime.of(2026, 8, 2, 12, 0));
        return outbox;
    }

    private static Workflow matchingWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(23L);
        workflow.setLegacyRuleId(29);
        workflow.setRuntimeGeneration(5L);
        return workflow;
    }

    private static WorkflowTriggerDispatch.EntityChange entityDispatch() {
        return new WorkflowTriggerDispatch.EntityChange(
            7,
            "deal",
            19,
            "deal.stage_changed",
            "event-key",
            LocalDateTime.of(2026, 8, 2, 12, 0)
                .toInstant(java.time.ZoneOffset.UTC));
    }
}
