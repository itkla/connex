package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowEventWait;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowEndConfig;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowWaitConfig;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.WorkflowEventWaitMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class WorkflowStepTransactionServiceTest {

    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private WorkflowRecordPolicyService recordPolicyService;
    @Mock private AutomationExecutor automationExecutor;
    @Mock private WorkflowNodeExecutor nodeExecutor;
    @Mock private WorkflowActionBindingService bindingService;
    @Mock private WorkspaceService workspaceService;
    @Mock private TaskMapper taskMapper;
    @Mock private WorkflowEventWaitMapper eventWaitMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private WorkspaceMapper placementWorkspaceMapper;

    private WorkflowStepTransactionService service;
    private WorkflowRun run;
    private WorkflowVersion version;
    private CompiledWorkflow compiled;
    private WorkflowExecutionPrincipal principal;

    @BeforeEach
    void setUp() {
        service = new WorkflowStepTransactionService(
            workflowRunMapper,
            workflowVersionMapper,
            principalService,
            recordGuard,
            recordPolicyService,
            automationExecutor,
            nodeExecutor,
            bindingService,
            workspaceService,
            taskMapper,
            eventWaitMapper);
        run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        run.setActorUserId(17);
        run.setAttributionUserId(17);
        version = new WorkflowVersion();
        version.setId(19L);
        User actor = new User();
        actor.setId(17);
        principal = new WorkflowExecutionPrincipal(
            actor, "member", 17, 17);
        RuleAction action = new RuleAction();
        action.setType("notify");
        WorkflowNode.Action node = new WorkflowNode.Action("action", action);
        WorkflowEdge edge = new WorkflowEdge(
            "action-end", "action", "end", WorkflowEdge.Outcome.NEXT);
        compiled = new CompiledWorkflow(
            "trigger",
            Map.of("action", node),
            Map.of("action", NodeType.ACTION),
            Map.of("action", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("action"),
            null);
        lenient().when(workflowRunMapper.getByIdInWorkspace(7, 31L)).thenReturn(run);
        lenient().when(workflowRunMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
        lenient().when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        lenient().when(principalService.resolveLocked(7, version)).thenReturn(principal);
        lenient().when(automationExecutor.runAs(
                eq(7), any(User.class), eq("member"), any()))
            .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(3).get());
    }

    @Test
    void actionAndCheckpointAreWrittenInTheSameOrderedBoundary() {
        when(nodeExecutor.execute(any(), any())).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "action", "end")).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "action", compiled);

        assertTrue(result.executed());
        InOrder order = inOrder(
            workflowRunMapper,
            workflowVersionMapper,
            principalService,
            recordGuard,
            nodeExecutor);
        order.verify(workflowRunMapper).getByIdInWorkspace(7, 31L);
        order.verify(workflowVersionMapper).getById(7, 11, 19L);
        order.verify(principalService).resolveLocked(7, version);
        order.verify(workflowRunMapper).getByIdForUpdate(7, 31L);
        order.verify(recordGuard).requireAccessible(run);
        order.verify(nodeExecutor).execute(any(), eq(compiled.node("action")));
        order.verify(workflowRunMapper).insertStep(any());
        order.verify(workflowRunMapper).advanceRun(7, 31L, "action", "end");
    }

    @Test
    void queuedDeliveryIdentityIsPersistedWithTheSuccessfulStep() {
        when(nodeExecutor.execute(any(), any())).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT,
                new WorkflowActionResult("delivery_queued", 71L)));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "action", "end")).thenReturn(1);

        service.execute(7, 31L, "action", compiled);

        ArgumentCaptor<ooo.klae.connex.backend.beans.WorkflowStepRun> step =
            ArgumentCaptor.forClass(ooo.klae.connex.backend.beans.WorkflowStepRun.class);
        verify(workflowRunMapper).insertStep(step.capture());
        assertEquals("delivery_queued", step.getValue().getActionOutcome());
        assertEquals(71L, step.getValue().getActionReferenceId());
    }

    @Test
    void failureBeforeCheckpointWritesNoStepOrCursor() {
        when(nodeExecutor.execute(any(), any())).thenThrow(
            new IllegalStateException("crash before commit"));

        assertThrows(
            IllegalStateException.class,
            () -> service.execute(7, 31L, "action", compiled));

        verify(workflowRunMapper, never()).insertStep(any());
        verify(workflowRunMapper, never()).advanceRun(
            anyInt(), anyLong(), any(), any());
    }

    @Test
    void replayAfterCommittedCheckpointDoesNotExecuteTheActionAgain() {
        run.setCurrentNodeId("end");

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "action", compiled);

        assertFalse(result.executed());
        verify(nodeExecutor, never()).execute(any(), any());
        verify(workflowRunMapper, never()).insertStep(any());
    }

    @Test
    void eachNodeUsesAnIndependentReadCommittedTransaction() throws Exception {
        Method execute = WorkflowStepTransactionService.class.getMethod(
            "execute", int.class, long.class, String.class, CompiledWorkflow.class);
        Transactional transaction = execute.getAnnotation(Transactional.class);

        assertNotNull(transaction);
        assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
    }

    @Test
    void schemaV2PolicyAndDecisionRunInsideTheLockedActorScope() {
        run.setCurrentNodeId("trigger");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", null);
        WorkflowEdge edge = new WorkflowEdge(
            "trigger-end", "trigger", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow schemaV2 = new CompiledWorkflow(
            2,
            "trigger",
            Map.of("trigger", trigger),
            Map.of("trigger", NodeType.TRIGGER),
            Map.of("trigger", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("trigger"),
            java.util.List.of(),
            null,
            null,
            null);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of()), Map.of(17, Set.of()));
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        when(nodeExecutor.execute(any(), eq(trigger))).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "trigger", "end")).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "trigger", schemaV2);

        assertTrue(result.executed());
        InOrder scopeOrder = inOrder(
            workflowRunMapper,
            automationExecutor,
            recordPolicyService,
            nodeExecutor);
        scopeOrder.verify(workflowRunMapper).getByIdForUpdate(7, 31L);
        scopeOrder.verify(automationExecutor).runAs(
            eq(7), eq(principal.principal()), eq("member"), any());
        scopeOrder.verify(recordPolicyService).stopReason(run, schemaV2, 17);
        scopeOrder.verify(automationExecutor).runAs(
            eq(7), eq(principal.principal()), eq("member"), any());
        scopeOrder.verify(nodeExecutor).execute(any(), eq(trigger));
    }

    @Test
    void realActorScopeProtectsPolicyAndRestoresThreadContextAfterSuccessAndFailure() {
        run.setCurrentNodeId("trigger");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", null);
        WorkflowEdge edge = new WorkflowEdge(
            "trigger-end", "trigger", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow schemaV2 = new CompiledWorkflow(
            2,
            "trigger",
            Map.of("trigger", trigger),
            Map.of("trigger", NodeType.TRIGGER),
            Map.of("trigger", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("trigger"),
            java.util.List.of(),
            null,
            null,
            null);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of()), Map.of(17, Set.of()));
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        when(placementWorkspaceMapper.getOrgId(7)).thenReturn(42);
        when(tenantCatalogResolver.resolveCatalog(42)).thenReturn("connex_workspace_7");
        when(nodeExecutor.execute(any(), eq(trigger))).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "trigger", "end")).thenReturn(1);
        TenantContext tenantContext = new TenantContext();
        AutomationScope scope = new AutomationScope();
        AutomationExecutor realExecutor = new AutomationExecutor(
            tenantContext,
            scope,
            new TenantWorkScope(
                tenantContext, tenantCatalogResolver, placementWorkspaceMapper));
        WorkflowStepTransactionService scopedService = new WorkflowStepTransactionService(
            workflowRunMapper,
            workflowVersionMapper,
            principalService,
            recordGuard,
            recordPolicyService,
            realExecutor,
            nodeExecutor,
            bindingService,
            workspaceService,
            taskMapper,
            eventWaitMapper);
        AtomicInteger policyCalls = new AtomicInteger();
        IllegalStateException expectedFailure = new IllegalStateException("policy failure");
        when(recordPolicyService.stopReason(run, schemaV2, 17)).thenAnswer(invocation -> {
            assertEquals(7, tenantContext.getWorkspaceId());
            assertEquals(42, tenantContext.getOrgId());
            assertEquals(17, tenantContext.getUserId());
            assertEquals("member", tenantContext.getRole());
            assertEquals("connex_workspace_7", tenantContext.getCatalog());
            assertSame(principal.principal(),
                SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            assertTrue(scope.isActive());
            if (policyCalls.incrementAndGet() == 2) {
                throw expectedFailure;
            }
            return null;
        });

        SecurityContextHolder.clearContext();
        try {
            assertTrue(scopedService.execute(7, 31L, "trigger", schemaV2).executed());
            assertFalse(tenantContext.isResolved());
            assertFalse(scope.isActive());
            assertNull(SecurityContextHolder.getContext().getAuthentication());

            IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> scopedService.execute(7, 31L, "trigger", schemaV2));
            assertSame(expectedFailure, failure);
            assertFalse(tenantContext.isResolved());
            assertFalse(scope.isActive());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        } finally {
            tenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void schemaV2ActionDiscoveryAndResolutionRunInsideActorScopes() {
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTargetUserId(23);
        WorkflowNode.Action node = new WorkflowNode.Action("action", action);
        WorkflowEdge edge = new WorkflowEdge(
            "action-end", "action", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow schemaV2 = new CompiledWorkflow(
            2,
            "action",
            Map.of("action", node),
            Map.of("action", NodeType.ACTION),
            Map.of("action", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("action"),
            java.util.List.of(),
            null,
            null,
            null);
        WorkflowActionBindingService.Discovery discovery =
            new WorkflowActionBindingService.Discovery(23);
        AtomicInteger scopeDepth = new AtomicInteger();
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of(), 23, Set.of()),
                Map.of(17, Set.of(), 23, Set.of()));
        when(principalService.resolve(7, version)).thenReturn(principal);
        when(automationExecutor.runAs(
                eq(7), eq(principal.principal()), eq("member"), any()))
            .thenAnswer(invocation -> {
                scopeDepth.incrementAndGet();
                try {
                    return invocation.<Supplier<?>>getArgument(3).get();
                } finally {
                    scopeDepth.decrementAndGet();
                }
            });
        when(bindingService.discover(run, action, schemaV2)).thenAnswer(invocation -> {
            assertTrue(scopeDepth.get() > 0);
            return discovery;
        });
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        when(bindingService.resolveLocked(
            run, action, discovery, authorization, schemaV2)).thenAnswer(invocation -> {
                assertTrue(scopeDepth.get() > 0);
                return action;
            });
        when(nodeExecutor.execute(any(), any())).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.IMMEDIATE,
                WorkflowEdge.Outcome.NEXT));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.advanceRun(7, 31L, "action", "end")).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "action", schemaV2);

        assertTrue(result.executed());
        assertEquals(0, scopeDepth.get());
        verify(bindingService).discover(run, action, schemaV2);
        verify(bindingService).resolveLocked(
            run, action, discovery, authorization, schemaV2);
    }

    @Test
    void delayUsesUtcStepEvidenceAndDatabaseTimedRunWaitInHonolulu() {
        run.setCurrentNodeId("delay");
        WorkflowNode.Delay delay = new WorkflowNode.Delay(
            "delay", new WorkflowDelayConfig(3_600));
        WorkflowEdge edge = new WorkflowEdge(
            "delay-end", "delay", "end", WorkflowEdge.Outcome.NEXT);
        CompiledWorkflow delayWorkflow = new CompiledWorkflow(
            "trigger",
            Map.of("delay", delay),
            Map.of("delay", NodeType.DELAY),
            Map.of("delay", Map.of(WorkflowEdge.Outcome.NEXT, edge)),
            java.util.List.of("delay"),
            null);
        when(workflowRunMapper.getOwnedByIdForUpdate(7, 31L, "owner"))
            .thenReturn(run);
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.waitForDelay(
            7, 31L, "delay", "owner", 3_600)).thenReturn(1);

        TimeZone originalTimezone = TimeZone.getDefault();
        WorkflowStepTransactionService.StepResult result;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            result = service.executeClaimed(
                7, 31L, "delay", delayWorkflow, "owner");
        } finally {
            TimeZone.setDefault(originalTimezone);
        }

        assertTrue(result.suspended());
        ArgumentCaptor<WorkflowStepRun> step = ArgumentCaptor.forClass(
            WorkflowStepRun.class);
        verify(workflowRunMapper).insertStep(step.capture());
        assertEquals("waiting", step.getValue().getStatus());
        assertEquals("none", step.getValue().getRetrySafety());
        assertTrue(step.getValue().getStartedAt().isAfter(
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        verify(workflowRunMapper).waitForDelay(
            7, 31L, "delay", "owner", 3_600);
    }

    @Test
    void eventWaitDerivesItsTimeoutFromDatabaseUtcInHonolulu() {
        run.setCurrentNodeId("wait");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        WorkflowNode.Wait wait = new WorkflowNode.Wait(
            "wait",
            new WorkflowWaitConfig(
                "event",
                "task.completed",
                new WorkflowWaitConfig.Source("task", "taskId"),
                300));
        CompiledWorkflow waitWorkflow = new CompiledWorkflow(
            2,
            "wait",
            Map.of("wait", wait),
            Map.of("wait", NodeType.WAIT),
            Map.of("wait", Map.of()),
            java.util.List.of("wait"),
            java.util.List.of(),
            null,
            null,
            null);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of()), Map.of(17, Set.of()));
        WorkflowStepRun source = new WorkflowStepRun();
        source.setId(41L);
        source.setStatus("succeeded");
        source.setActionOutputsJson("{\"taskId\":91}");
        Task task = new Task();
        task.setId(91);
        LocalDateTime databaseNow = LocalDateTime.of(2026, 8, 3, 22, 30);
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        when(workflowRunMapper.getOwnedByIdForUpdate(7, 31L, "owner"))
            .thenReturn(run);
        when(workflowRunMapper.getStepByNodeForUpdate(7, 31L, "task"))
            .thenReturn(source);
        when(workflowRunMapper.currentTimestamp(7, 11)).thenReturn(databaseNow);
        when(taskMapper.getTaskByIdForUpdate(7, 91)).thenReturn(task);
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.waitForEvent(
            7, 31L, "wait", "owner", databaseNow.plusSeconds(300))).thenReturn(1);

        TimeZone originalTimezone = TimeZone.getDefault();
        WorkflowStepTransactionService.StepResult result;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            result = service.executeClaimed(
                7, 31L, "wait", waitWorkflow, "owner");
        } finally {
            TimeZone.setDefault(originalTimezone);
        }

        assertTrue(result.suspended());
        ArgumentCaptor<WorkflowEventWait> eventWait = ArgumentCaptor.forClass(
            WorkflowEventWait.class);
        verify(eventWaitMapper).insertWait(eventWait.capture());
        assertEquals(databaseNow.plusSeconds(300), eventWait.getValue().getTimeoutAt());
        verify(workflowRunMapper).waitForEvent(
            7, 31L, "wait", "owner", databaseNow.plusSeconds(300));
    }

    @Test
    void stoppedEndWithoutAConfiguredReasonStillStopsTheRun() {
        run.setCurrentNodeId("end");
        WorkflowNode.End end = new WorkflowNode.End(
            "end", new WorkflowEndConfig("stopped", null));
        CompiledWorkflow stoppedWorkflow = new CompiledWorkflow(
            2,
            "end",
            Map.of("end", end),
            Map.of("end", NodeType.END),
            Map.of("end", Map.of()),
            java.util.List.of("end"),
            java.util.List.of(),
            null,
            null,
            null);
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        WorkspaceService.LockedPermissionSnapshot authorization =
            new WorkspaceService.LockedPermissionSnapshot(
                Map.of(17, Set.of()), Map.of(17, Set.of()));
        when(workspaceService.lockAndRequirePermissionsSnapshot(eq(7), any()))
            .thenReturn(authorization);
        when(principalService.resolveLocked(7, version, authorization))
            .thenReturn(principal);
        when(nodeExecutor.execute(any(), eq(end))).thenReturn(
            new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.TERMINAL, null));
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.stopRun(
            eq(7), eq(31L), eq("end"), isNull(), any())).thenReturn(1);

        WorkflowStepTransactionService.StepResult result = service.execute(
            7, 31L, "end", stoppedWorkflow);

        assertTrue(result.terminal());
        verify(workflowRunMapper).stopRun(
            eq(7), eq(31L), eq("end"), isNull(), any());
        verify(workflowRunMapper, never()).completeRun(
            anyInt(), anyLong(), any(), any());
    }
}
