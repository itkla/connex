package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

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
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

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
            properties);
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
