package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Exercises operation reuse through real automation identity installation and action dispatch. */
@ExtendWith(MockitoExtension.class)
class AutomationRecordVisibilityScopeTest {

    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantCatalogResolver tenantCatalogResolver;
    @Mock private DealService dealService;

    private final TenantContext tenantContext = new TenantContext();
    private final AutomationScope automationScope = new AutomationScope();
    private final User principal = new User();
    private TenantWorkScope tenantWorkScope;
    private RecordVisibilityScopeControlAccess scopes;
    private AutomationExecutor automationExecutor;

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        principal.setId(17);
        tenantWorkScope = new TenantWorkScope(tenantContext, tenantCatalogResolver, workspaceMapper);
        scopes = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(workspaceMapper, tenantWorkScope, tenantContext,
                mock(PlatformTransactionManager.class)), tenantContext);
        automationExecutor = new AutomationExecutor(tenantContext, automationScope, tenantWorkScope);
        Workspace workspace = new Workspace();
        workspace.setId(7);
        workspace.setOrgId(900);
        workspace.setName("Automation workspace");
        Workspace sibling = new Workspace();
        sibling.setId(11);
        sibling.setOrgId(900);
        sibling.setName("Sibling workspace");
        when(workspaceMapper.getOrgId(7)).thenReturn(900);
        when(tenantCatalogResolver.resolveCatalog(900)).thenReturn("cnx_automation");
        when(workspaceMapper.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace, sibling));
    }

    @AfterEach
    void clearThreadState() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
        assertFalse(automationScope.isActive());
        assertNull(automationScope.origin());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void operationServesTheSameScopeWhenRunAsInstallsTheSameWorkspace(boolean initiallyResolved) {
        assertFalse(tenantContext.isResolved());
        tenantWorkScope.inWorkspace(7, () -> {
            TenantContext.State previous = tenantContext.snapshot();
            if (initiallyResolved) {
                tenantContext.set(7, 900, 42, "owner", "cnx_automation");
            }
            try {
                scopes.withWorkspaceScope(7, () -> {
                    RecordVisibilityScope original = scopes.getForWorkspace(7);
                    RecordVisibilityScope during = automationExecutor.runAs(7, principal, "member", () -> {
                        assertTrue(tenantContext.isResolved());
                        assertEquals(7, tenantContext.getWorkspaceId());
                        assertEquals(900, tenantContext.getOrgId());
                        assertEquals("cnx_automation", tenantContext.getCatalog());
                        return scopes.getForWorkspace(7);
                    });
                    assertSame(original, during);
                    assertEquals(initiallyResolved, tenantContext.isResolved());
                    assertSame(original, scopes.getForWorkspace(7));
                    return null;
                });
            } finally {
                tenantContext.restore(previous);
            }
        });
        assertFalse(tenantContext.isResolved());
        assertNull(tenantContext.getCatalog());
        verify(workspaceMapper).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void operationRefusesRunAsForAnotherWorkspaceEvenInTheSameOrganizationAndCatalog() {
        when(workspaceMapper.getOrgId(11)).thenReturn(900);
        tenantWorkScope.inWorkspace(7, () -> scopes.withWorkspaceScope(7, () -> {
            RecordVisibilityScope original = scopes.getForWorkspace(7);
            assertTrue(original.workspaceIds().contains(11));
            automationExecutor.runAs(11, principal, "member", () -> {
                assertEquals(11, tenantContext.getWorkspaceId());
                assertEquals(900, tenantContext.getOrgId());
                assertEquals("cnx_automation", tenantContext.getCatalog());
                assertThrows(IllegalStateException.class, () -> scopes.getForWorkspace(7));
                return null;
            });
            assertFalse(tenantContext.isResolved());
            assertSame(original, scopes.getForWorkspace(7));
            return null;
        }));
        verify(workspaceMapper).findOrganizationWorkspacesForShare(7);
        verify(workspaceMapper, never()).findOrganizationWorkspacesForShare(11);
    }

    @Test
    void operationRefusesOrganizationAndCatalogDriftAfterInstallingIdentity() {
        tenantWorkScope.inWorkspace(7, () -> scopes.withWorkspaceScope(7, () ->
            automationExecutor.runAs(7, principal, "member", () -> {
                RecordVisibilityScope original = scopes.getForWorkspace(7);
                TenantContext.State previous = tenantContext.snapshot();
                tenantContext.set(7, 901, 17, "member", "cnx_automation");
                try {
                    assertThrows(IllegalStateException.class, () -> scopes.getForWorkspace(7));
                } finally {
                    tenantContext.restore(previous);
                }
                assertThrows(IllegalStateException.class, () ->
                    tenantWorkScope.withCatalog("cnx_other", () -> scopes.getForWorkspace(7)));
                assertSame(original, scopes.getForWorkspace(7));
                return null;
            })));
        verify(workspaceMapper).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void canonicalChangeStageNodeReusesTheSchedulerOperationScope() {
        RuleAction action = changeStage(99);
        WorkflowNodeExecutor nodeExecutor = new WorkflowNodeExecutor(
            actionExecutor(), automationExecutor, new WorkflowNodeDecisionService(mock(SegmentService.class)),
            mock(WorkflowActionGuard.class));
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setWorkflowId(23);
        run.setId(31L);
        run.setExecutionMode("user");
        run.setTriggerType("entity_change");
        run.setRecordType("deal");
        run.setRecordId(41);
        WorkflowNodeExecutionContext context = new WorkflowNodeExecutionContext(
            run, new WorkflowVersion(), mock(CompiledWorkflow.class),
            new WorkflowExecutionPrincipal(principal, "member", 17, 17));
        List<RecordVisibilityScope> actionScopes = new ArrayList<>();
        when(dealService.changeStage(41, 99)).thenAnswer(invocation -> {
            assertTrue(tenantContext.isResolved());
            assertEquals(AutomationScope.Origin.workflow(23, 31L, "user"), automationScope.origin());
            actionScopes.add(scopes.getForWorkspace(7));
            return null;
        });

        tenantWorkScope.inWorkspace(7, () -> scopes.withWorkspaceScope(7, () -> {
            assertFalse(tenantContext.isResolved());
            RecordVisibilityScope original = scopes.getForWorkspace(7);
            WorkflowStepTransition transition = nodeExecutor.execute(context, new WorkflowNode.Action("stage", action));
            assertEquals(WorkflowEdge.Outcome.NEXT, transition.outcome());
            assertEquals(WorkflowStepTransition.Continuation.IMMEDIATE, transition.continuation());
            assertEquals(1, actionScopes.size());
            assertSame(original, actionScopes.getFirst());
            assertFalse(tenantContext.isResolved());
            return null;
        }));

        verify(dealService).changeStage(41, 99);
        verify(workspaceMapper).findOrganizationWorkspacesForShare(7);
    }

    @Test
    void legacyListenerDispatchReusesTheOperationScopeForEachStageChange() {
        RuleMapper rules = mock(RuleMapper.class);
        UserMapper users = mock(UserMapper.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkflowRuntimeClaimService claims = mock(WorkflowRuntimeClaimService.class);
        WorkflowRuntimeProperties properties = mock(WorkflowRuntimeProperties.class);
        ObjectMapper json = new ObjectMapper();
        Rule rule = new Rule();
        rule.setId(23);
        rule.setWorkspaceId(7);
        rule.setRecordType("deal");
        rule.setExecutionMode("user");
        rule.setRunAsUserId(17);
        rule.setTriggerConfig("{\"type\":\"entity_change\",\"events\":[\"deal.updated\"]}");
        rule.setActionsJson(json.writeValueAsString(List.of(changeStage(99), changeStage(100))));
        RuleExecution execution = new RuleExecution();
        execution.setId(31);
        execution.setWorkspaceId(7);
        when(rules.getEnabledByTrigger(7, "entity_change")).thenReturn(List.of(rule));
        when(claims.claimLegacyEntity(eq(rule), any(), any())).thenReturn(
            new WorkflowRuntimeClaimService.LegacyClaim(execution, "event-7", true, false, false));
        when(workspaces.getRole(7, 17)).thenReturn("member");
        when(users.getUserById(17)).thenReturn(principal);
        List<RecordVisibilityScope> actionScopes = new ArrayList<>();
        for (int stageId : List.of(99, 100)) {
            when(dealService.changeStage(41, stageId)).thenAnswer(invocation -> {
                assertTrue(tenantContext.isResolved());
                assertEquals(AutomationScope.Origin.rule(23, 31, "user"), automationScope.origin());
                actionScopes.add(scopes.getForWorkspace(7));
                return null;
            });
        }
        RuleEngineService engine = new RuleEngineService(
            rules, scopes, mock(DealMapper.class), mock(PersonMapper.class), mock(SegmentService.class),
            actionExecutor(), new RuleActionIsolation(mock(PlatformTransactionManager.class),
                mock(SqlSessionTemplate.class), new WorkflowActionRetryPolicy(properties)),
            automationExecutor, users, workspaces, mock(SystemActor.class), json, claims,
            mock(WorkflowMapper.class), mock(WorkflowTriggeredSendGate.class), mock(AuditService.class));
        RuleTriggerListener listener = new RuleTriggerListener(properties, engine, tenantWorkScope);

        assertFalse(tenantContext.isResolved());
        listener.onTrigger(new RuleTriggerEvent(7, "deal", 41, "deal.updated", "event-7", Instant.EPOCH));

        assertEquals(2, actionScopes.size());
        assertSame(actionScopes.getFirst(), actionScopes.getLast());
        assertFalse(tenantContext.isResolved());
        assertNull(tenantContext.getCatalog());
        verify(rules).updateExecution(7, 31, "matched", null);
        verify(dealService).changeStage(41, 99);
        verify(dealService).changeStage(41, 100);
        verify(workspaceMapper, times(1)).findOrganizationWorkspacesForShare(7);
    }

    private RuleActionExecutor actionExecutor() {
        return new RuleActionExecutor(
            mock(TaskService.class), mock(ActivityService.class), mock(CompanyService.class),
            mock(PersonService.class), mock(LeadResponseSlaService.class), dealService, mock(NoteService.class),
            mock(NotificationDelivery.class), mock(TagMapper.class), mock(DealDocumentMapper.class),
            mock(CampaignTriggeredSendService.class), mock(WorkspaceService.class), tenantContext);
    }

    private static RuleAction changeStage(int stageId) {
        RuleAction action = new RuleAction();
        action.setType("change_stage");
        action.setTargetStageId(stageId);
        return action;
    }
}
