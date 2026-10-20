package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class WorkflowTriggerOutboxDeliveryServiceTest {

    @Mock private WorkflowTriggerOutboxMapper outboxMapper;
    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private SegmentMapper segmentMapper;
    @Mock private SegmentService segmentService;
    @Mock private WorkflowRuntimeClaimService claimService;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private RuleEngineService ruleEngineService;
    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkflowTriggeredSendGate triggeredSendGate;
    @Mock private AuditService auditService;

    private WorkflowTriggerOutboxDeliveryService service;
    private WorkflowExecutionPrincipal principal;
    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    @BeforeEach
    void setUp() {
        service = new WorkflowTriggerOutboxDeliveryService(
            outboxMapper,
            workflowMapper,
            versionMapper,
            segmentMapper,
            segmentService,
            claimService,
            principalService,
            ruleEngineService,
            properties,
            triggeredSendGate,
            auditService);
        lenient().when(triggeredSendGate.recipientLimit()).thenReturn(200);
        User actor = new User();
        actor.setId(17);
        principal = new WorkflowExecutionPrincipal(
            actor, "member", 17, 17, java.util.Set.of());
        lenient().when(principalService.resolveLocked(anyInt(), any(WorkflowVersion.class)))
            .thenReturn(principal);
    }

    @Test
    void entityDeliveryBracketsCanonicalClaimWithBothPersistedOwnerChecks() {
        WorkflowTriggerOutbox outbox = entityOutbox();
        Workflow workflow = matchingWorkflow();
        when(outboxMapper.getById(7, 31L)).thenReturn(outbox);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease"))
            .thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        WorkflowVersion version = new WorkflowVersion();
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
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
            principalService,
            ruleEngineService,
            claimService,
            outboxMapper);
        order.verify(outboxMapper).getById(7, 31L);
        order.verify(workflowMapper).getById(7, 11);
        order.verify(versionMapper).getById(7, 11, 23L);
        order.verify(principalService).resolveLocked(7, version);
        order.verify(outboxMapper).ensureWorkspaceGate(7);
        order.verify(outboxMapper).getOwnedForUpdate(7, 31L, "lease");
        order.verify(workflowMapper).getByIdForUpdate(7, 11);
        order.verify(ruleEngineService)
            .onEntityChangeForWorkflow(11, entityDispatch(), principal);
        order.verify(claimService).claimOutbox(outbox, 19);
        order.verify(ruleEngineService)
            .onEntityChangeForWorkflow(11, entityDispatch(), principal);
        order.verify(outboxMapper).complete(7, 31L, "lease");
        verify(principalService, never()).resolve(7, version);
        verifyNoInteractions(segmentMapper, segmentService);
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
        when(outboxMapper.getById(7, 31L)).thenReturn(outbox);
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
        when(outboxMapper.getById(7, 31L)).thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        when(principalService.resolveLocked(7, version)).thenThrow(
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
        when(outboxMapper.getById(7, 31L)).thenReturn(outbox);
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

    @Test
    void twoHundredRecordsReadOneScopePerDeliveryPageTransaction() {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        TestTransactionManager manager = new TestTransactionManager();
        WorkflowTriggerOutboxDeliveryService delivery = transactionalDelivery(manager, realSegments(manager, workspaces));
        WorkflowTriggerOutbox outbox = scheduleOutbox();
        outbox.setRecordScanUpperId(400);
        SegmentDefinition definition = noActivity();
        WorkflowVersion version = prepareDelivery(outbox);
        CompiledWorkflow compiled = mock(CompiledWorkflow.class);
        when(compiled.nodes()).thenReturn(Map.of());
        when(claimService.outboxScheduleEnrollment(outbox)).thenReturn(
            new WorkflowRuntimeClaimService.ScheduleEnrollment(
                11, version, compiled, new WorkflowNode.Condition("enrollment", definition), 17));
        when(properties.maxScheduleRecordsPerPage()).thenReturn(200);
        when(segmentMapper.entityIdsPage(7, "company", 0, 400, 200))
            .thenReturn(IntStream.rangeClosed(1, 200).boxed().toList());
        when(segmentMapper.entityIdsPage(7, "company", 200, 400, 200))
            .thenReturn(IntStream.rangeClosed(201, 400).boxed().toList());
        when(outboxMapper.saveSchedulePage(7, 31L, "lease", 200, 0, false)).thenReturn(1);
        when(outboxMapper.saveSchedulePage(7, 31L, "lease", 400, 0, true)).thenReturn(1);

        assertEquals(WorkflowTriggerOutboxDeliveryService.DeliveryResult.COMPLETED, delivery.deliver(7, 31L, "lease"));

        assertEquals(1, manager.beginCount());
        verify(workspaces).findOrganizationWorkspacesForShare(7);
        verify(segmentMapper, times(200)).entityIdInWorkspace(eq(7), eq("company"), anyInt());
        outbox.setRecordScanAfterId(200);

        assertEquals(WorkflowTriggerOutboxDeliveryService.DeliveryResult.COMPLETED, delivery.deliver(7, 31L, "lease"));

        assertEquals(2, manager.beginCount());
        verify(workspaces, times(2)).findOrganizationWorkspacesForShare(7);
        verify(segmentMapper, times(400)).companyIdsNoActivitySince(eq(7), eq("[7]"), eq(30), anyList());
        verify(claimService, times(400)).claimOutbox(eq(outbox), anyInt());
        verify(ruleEngineService, times(800)).runScheduleRecordForWorkflow(eq(11), any(), anyInt(), eq(principal));
        verify(outboxMapper).saveSchedulePage(7, 31L, "lease", 200, 0, false);
        verify(outboxMapper).saveSchedulePage(7, 31L, "lease", 400, 0, true);
    }

    @Test
    void entityDeliveryRepeatedConditionChecksReuseOneScope() {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        TestTransactionManager manager = new TestTransactionManager();
        SegmentService evaluator = realSegments(manager, workspaces);
        WorkflowTriggerOutboxDeliveryService delivery = transactionalDelivery(manager, evaluator);
        WorkflowTriggerOutbox outbox = entityOutbox();
        outbox.setRecordType("company");
        outbox.setTriggerEvent("company.updated");
        prepareDelivery(outbox);
        when(outboxMapper.complete(7, 31L, "lease")).thenReturn(1);
        SegmentDefinition definition = noActivity();
        doAnswer(invocation -> {
            assertTrue(evaluator.matchesEntity(7, 17, "company", definition, 19));
            return null;
        }).when(ruleEngineService).onEntityChangeForWorkflow(eq(11), any(), eq(principal));

        assertEquals(WorkflowTriggerOutboxDeliveryService.DeliveryResult.COMPLETED, delivery.deliver(7, 31L, "lease"));

        assertEquals(1, manager.beginCount());
        verify(workspaces).findOrganizationWorkspacesForShare(7);
        verify(segmentMapper, times(2)).companyIdsNoActivitySince(7, "[7]", 30, List.of(19));
        verify(ruleEngineService, times(2)).onEntityChangeForWorkflow(eq(11), any(), eq(principal));
    }

    private WorkflowVersion prepareDelivery(WorkflowTriggerOutbox outbox) {
        Workflow workflow = matchingWorkflow();
        WorkflowVersion version = new WorkflowVersion();
        version.setId(23L);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        when(outboxMapper.getById(7, 31L)).thenReturn(outbox);
        when(outboxMapper.getOwnedForUpdate(7, 31L, "lease")).thenReturn(outbox);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(versionMapper.getById(7, 11, 23L)).thenReturn(version);
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        return version;
    }

    private SegmentService realSegments(TestTransactionManager manager, WorkspaceMapper workspaces) {
        tenantContext.set(7, 900, 17, "member", null);
        Workspace anchor = new Workspace();
        anchor.setId(7);
        anchor.setOrgId(900);
        anchor.setName("Anchor");
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(anchor));
        RecordVisibilityScopeControlAccess scopes = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(workspaces,
                new TenantWorkScope(tenantContext, mock(TenantCatalogResolver.class), workspaces), tenantContext, manager),
            tenantContext);
        when(segmentMapper.entityIdInWorkspace(eq(7), eq("company"), anyInt())).thenReturn(true);
        when(segmentMapper.companyIdsNoActivitySince(eq(7), eq("[7]"), eq(30), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(3));
        return new SegmentService(mock(WorkspaceService.class), mock(AuthService.class), mock(ScoringService.class),
            mock(DealRiskService.class), segmentMapper, mock(PersonEdgeReadService.class), mock(PersonMapper.class),
            mock(TagMapper.class), new SegmentCatalog(), scopes);
    }

    private WorkflowTriggerOutboxDeliveryService transactionalDelivery(TestTransactionManager manager, SegmentService evaluator) {
        WorkflowTriggerOutboxDeliveryService target = new WorkflowTriggerOutboxDeliveryService(
            outboxMapper, workflowMapper, versionMapper, segmentMapper, evaluator, claimService, principalService,
            ruleEngineService, properties, triggeredSendGate, auditService);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (WorkflowTriggerOutboxDeliveryService) proxy.getProxy();
    }

    private static SegmentDefinition noActivity() {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey("no_activity");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
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
