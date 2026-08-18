package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeOwnershipServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private WorkflowPrincipalLockService principalLockService;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private LegacyWorkflowGraphConverter graphConverter;
    @Mock private WorkflowRuntimeProperties runtimeProperties;
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkflowService workflowService;
    @Mock private AuditService auditService;

    private WorkflowRuntimeOwnershipService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRuntimeOwnershipService(
            workflowMapper,
            workflowVersionMapper,
            workflowRunMapper,
            ruleMapper,
            principalLockService,
            definitionValidator,
            canonicalizer,
            graphConverter,
            runtimeProperties,
            workspaceService,
            workflowService,
            auditService);
    }

    @Test
    void disabledDeploymentGateRefusesCanonicalOwnershipBeforeDatabaseAccess() {
        when(runtimeProperties.enabled()).thenReturn(false);

        assertThrows(
            ConflictException.class,
            () -> service.cutOverToCanonical(11, 19L));

        verifyNoInteractions(workflowMapper, ruleMapper, workflowVersionMapper);
    }

    @Test
    void staleExpectedVersionFailsBeforeAnyOwnershipWrite() {
        when(runtimeProperties.enabled()).thenReturn(true);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        Workflow workflow = workflow("legacy");
        workflow.setActiveVersionId(20L);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);

        assertThrows(
            ConflictException.class,
            () -> service.cutOverToCanonical(11, 19L));

        verify(workflowMapper, never()).compareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void cutoverDisablesLegacyBeforeCompareAndSwap() {
        when(runtimeProperties.enabled()).thenReturn(true);
        Workflow workflow = workflow("legacy");
        Rule rule = rule(true);
        stubLock(workflow, rule);
        stubCompiledVersion();
        when(ruleMapper.updateEnabled(7, 13, false)).thenReturn(1);
        when(workflowMapper.compareAndSwapRuntimeOwner(
            7, 11, 19L, "legacy", "canonical", 9)).thenReturn(1);

        service.cutOverToCanonical(11, 19L);

        InOrder order = inOrder(ruleMapper, workflowMapper);
        order.verify(ruleMapper).updateEnabled(7, 13, false);
        order.verify(workflowMapper).compareAndSwapRuntimeOwner(
            7, 11, 19L, "legacy", "canonical", 9);
        verify(auditService).record(
            eq("workflow.runtime.cutover"),
            eq("workflow"),
            eq(11),
            eq("Workflow 11"),
            eq("Workflow runtime changed to canonical"),
            eq(java.util.Map.of("activeVersionId", 19L)));
    }

    @Test
    void repeatedCutoverIsIdempotentAndDoesNotRewriteEitherLedger() {
        when(runtimeProperties.enabled()).thenReturn(true);
        Workflow workflow = workflow("canonical");
        Rule rule = rule(false);
        stubLock(workflow, rule);

        service.cutOverToCanonical(11, 19L);

        verify(ruleMapper, never()).updateEnabled(anyInt(), anyInt(), anyBoolean());
        verify(workflowMapper, never()).compareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void incompatibleCanonicalGraphCannotRollbackToLegacy() {
        Workflow workflow = workflow("canonical");
        Rule rule = rule(false);
        stubLock(workflow, rule);
        stubCompiledVersion();
        when(canonicalizer.parseCanvas("{}")).thenReturn(null);
        when(graphConverter.project(any())).thenThrow(
            new BadRequestException("branching graph"));

        assertThrows(
            ConflictException.class,
            () -> service.rollBackToLegacy(11, 19L));

        verify(workflowMapper, never()).compareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void scheduleEnrollmentCutoverRefusalSurfacesTheStableDiagnostic() {
        when(runtimeProperties.enabled()).thenReturn(true);
        Workflow workflow = workflow("legacy");
        Rule rule = rule(true);
        stubLock(workflow, rule);
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Schedule");
        WorkflowDefinition definition = new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Action("action", action),
                new WorkflowNode.End("end")),
            List.of(
                new WorkflowEdge(
                    "trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge(
                    "action-end", "action", "end", WorkflowEdge.Outcome.NEXT)));
        stubCanonicalVersion(definition);
        WorkflowDiagnosticDto diagnostic = new WorkflowDiagnosticDto(
            WorkflowDiagnosticCode.SCHEDULE_ENROLLMENT_CONDITION_REQUIRED,
            "trigger",
            "trigger-action",
            "config.type",
            Map.of());
        when(definitionValidator.validate("company", "user", definition)).thenThrow(
            new WorkflowDefinitionValidationException(
                "Workflow schedule trigger must immediately target its enrollment condition",
                diagnostic));

        WorkflowDefinitionValidationException failure = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> service.cutOverToCanonical(11, 19L));

        assertEquals(
            WorkflowDiagnosticCode.SCHEDULE_ENROLLMENT_CONDITION_REQUIRED,
            failure.diagnostic().code());
        verify(ruleMapper, never()).updateEnabled(anyInt(), anyInt(), anyBoolean());
        verify(workflowMapper, never()).compareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void delayBearingActiveVersionCannotRollbackToLegacy() {
        Workflow workflow = workflow("canonical");
        Rule rule = rule(false);
        stubLock(workflow, rule);
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        WorkflowDefinition definition = new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Delay("delay", new WorkflowDelayConfig(60)),
                new WorkflowNode.End("end")),
            List.of(
                new WorkflowEdge(
                    "trigger-delay", "trigger", "delay", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge(
                    "delay-end", "delay", "end", WorkflowEdge.Outcome.NEXT)));
        stubCompiledVersion(definition);
        when(canonicalizer.parseCanvas("{}")).thenReturn(null);
        when(graphConverter.project(argThat(converted ->
                converted.definition().nodes().stream()
                    .anyMatch(WorkflowNode.Delay.class::isInstance))))
            .thenThrow(new BadRequestException("delay graph"));

        ConflictException failure = assertThrows(
            ConflictException.class,
            () -> service.rollBackToLegacy(11, 19L));

        assertEquals(
            "The active workflow version cannot be projected to the legacy runtime",
            failure.getMessage());
        verify(ruleMapper, never()).update(any());
        verify(workflowMapper, never()).compareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void firstRollbackWithCanonicalRunHistoryFailsBeforeProjectionOrOwnerWrite() {
        Workflow workflow = workflow("canonical");
        workflow.setLegacyRuleId(null);
        stubLock(workflow, null);
        when(workflowRunMapper.hasRunHistory(7, 11)).thenReturn(true);

        assertThrows(
            ConflictException.class,
            () -> service.rollBackToLegacy(11, 19L));

        verifyNoInteractions(graphConverter);
        verify(ruleMapper, never()).insert(any());
        verify(workflowMapper, never()).attachLegacyRuleAndCompareAndSwapRuntimeOwner(
            anyInt(), anyInt(), anyLong(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void systemOwnershipTransitionRequiresTheBuiltInAdminGate() {
        when(runtimeProperties.enabled()).thenReturn(true);
        Workflow workflow = workflow("canonical");
        WorkflowVersion version = version();
        version.setExecutionMode("system");
        version.setCreatedById(17);
        Rule rule = rule(false);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        when(ruleMapper.getById(7, 13)).thenReturn(rule);
        when(principalLockService.lockSystemMutation(7, 9, Set.of(17)))
            .thenReturn(new LockedPrincipals(Set.of(17), Set.of(17)));
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getByIdForUpdate(7, 11, 19L)).thenReturn(version);
        when(ruleMapper.getByIdForUpdate(7, 13)).thenReturn(rule);

        service.cutOverToCanonical(11, 19L);

        verify(principalLockService).lockSystemMutation(7, 9, Set.of(17));
        verify(principalLockService, never()).lockUserMutation(
            anyInt(), anyInt(), any(), any());
    }

    private void stubLock(Workflow workflow, Rule rule) {
        WorkflowVersion version = version();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        if (workflow.getLegacyRuleId() != null) {
            when(ruleMapper.getById(7, 13)).thenReturn(rule);
        }
        when(principalLockService.lockUserMutation(7, 9, Set.of(), Set.of()))
            .thenReturn(new LockedPrincipals(Set.of(), Set.of()));
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getByIdForUpdate(7, 11, 19L)).thenReturn(version);
        if (workflow.getLegacyRuleId() != null) {
            when(ruleMapper.getByIdForUpdate(7, 13)).thenReturn(rule);
        }
    }

    private void stubCompiledVersion() {
        stubCompiledVersion(new WorkflowDefinition(
            1, "trigger", List.of(), List.of()));
    }

    private void stubCompiledVersion(WorkflowDefinition definition) {
        stubCanonicalVersion(definition);
        when(definitionValidator.validate("company", "user", definition))
            .thenReturn(null);
    }

    private void stubCanonicalVersion(WorkflowDefinition definition) {
        CanonicalDraft canonical = new CanonicalDraft(
            "Workflow", null, "company", "user", "{}", "{}", new byte[32]);
        when(canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "company", "user", "{}", "{}"))
            .thenReturn(canonical);
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
    }

    private static Workflow workflow(String owner) {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(13);
        workflow.setActiveVersionId(19L);
        workflow.setRuntimeOwner(owner);
        workflow.setEnabled(true);
        return workflow;
    }

    private static WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(19L);
        version.setWorkspaceId(7);
        version.setWorkflowId(11);
        version.setName("Workflow");
        version.setRecordType("company");
        version.setExecutionMode("user");
        version.setDefinitionJson("{}");
        version.setCanvasJson("{}");
        version.setDefinitionHash(new byte[32]);
        return version;
    }

    private static Rule rule(boolean enabled) {
        Rule rule = new Rule();
        rule.setId(13);
        rule.setWorkspaceId(7);
        rule.setEnabled(enabled);
        return rule;
    }
}
