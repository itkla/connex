package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeClaimServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowTriggerOutboxMapper workflowTriggerOutboxMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private DealMapper dealMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private SystemActor systemActor;
    @Mock private CompiledWorkflow compiled;

    private WorkflowRuntimeClaimService service;
    private Workflow workflow;
    private WorkflowVersion version;
    private WorkflowTriggerDispatch.EntityChange dispatch;

    @BeforeEach
    void setUp() {
        service = new WorkflowRuntimeClaimService(
            workflowMapper,
            workflowVersionMapper,
            workflowRunMapper,
            workflowTriggerOutboxMapper,
            ruleMapper,
            dealMapper,
            canonicalizer,
            definitionValidator,
            new WorkflowDedupeKey(),
            systemActor);
        workflow = workflow("canonical");
        version = version();
        dispatch = new WorkflowTriggerDispatch.EntityChange(
            7,
            "company",
            41,
            "company.updated",
            "event-7",
            Instant.parse("2026-08-02T12:00:00Z"));
    }

    @Test
    void legacyClaimWinningBeforeCutoverSuppressesCanonicalReplay() {
        stubCanonicalCompilation();
        String expectedKey = entityDedupeKey(13);
        when(ruleMapper.getExecutionByDedupe(7, 13, expectedKey))
            .thenReturn(new RuleExecution());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        assertNull(claim.run());
        verify(ruleMapper).getExecutionByDedupe(7, 13, expectedKey);
        verify(workflowRunMapper, never()).insertRun(any());
    }

    @Test
    void canonicalClaimWinningBeforeRollbackSuppressesLegacyReplay() {
        Workflow legacyOwner = workflow("legacy");
        Rule rule = rule();
        String expectedKey = entityDedupeKey(13);
        when(workflowMapper.getByLegacyRuleIdForUpdate(7, 13)).thenReturn(legacyOwner);
        when(workflowRunMapper.getByDedupe(7, 11, expectedKey))
            .thenReturn(new WorkflowRun());

        WorkflowRuntimeClaimService.LegacyClaim claim = service.claimLegacyEntity(
            rule, entityTrigger(), dispatch);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        verify(workflowRunMapper).getByDedupe(7, 11, expectedKey);
        verify(ruleMapper, never()).insertExecution(any());
    }

    @Test
    void unpairedLegacyRuleClaimsExactlyOnceAndDedupesRedelivery() {
        Rule rule = rule();
        RuleExecution persisted = new RuleExecution();
        persisted.setId(71);
        when(workflowMapper.getByLegacyRuleIdForUpdate(7, 13)).thenReturn(null);
        when(ruleMapper.getExecutionByDedupe(eq(7), eq(13), anyString()))
            .thenReturn(null, persisted);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<RuleExecution>getArgument(0).setId(71);
            return null;
        }).when(ruleMapper).insertExecution(any());

        WorkflowRuntimeClaimService.LegacyClaim first = service.claimLegacyEntity(
            rule, entityTrigger(), dispatch);
        WorkflowRuntimeClaimService.LegacyClaim replay = service.claimLegacyEntity(
            rule, entityTrigger(), dispatch);

        assertTrue(first.started());
        assertFalse(first.replayed());
        assertFalse(replay.started());
        assertTrue(replay.replayed());
        ArgumentCaptor<RuleExecution> execution = ArgumentCaptor.forClass(RuleExecution.class);
        verify(ruleMapper, times(1)).insertExecution(execution.capture());
        assertEquals(first.dedupeKey(), execution.getValue().getDedupeKey());
        assertEquals(7, execution.getValue().getWorkspaceId());
        verify(workflowRunMapper, never()).getByDedupe(anyInt(), anyInt(), anyString());
    }

    @Test
    void persistedOwnerRefusesTheWrongEngineInBothDirections() {
        Workflow legacyOwner = workflow("legacy");
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(legacyOwner);
        assertTrue(service.claimEntity(11, dispatch).rejected());

        Workflow canonicalOwner = workflow("canonical");
        Rule rule = rule();
        when(workflowMapper.getByLegacyRuleIdForUpdate(7, 13))
            .thenReturn(canonicalOwner);
        assertTrue(service.claimLegacyEntity(
            rule, entityTrigger(), dispatch).rejected());

        verify(workflowRunMapper, never()).insertRun(any());
        verify(ruleMapper, never()).insertExecution(any());
    }

    @Test
    void duplicateInsertReturnsTheExistingPinnedRunWithoutASecondClaim() {
        stubCanonicalCompilation();
        WorkflowRun replay = new WorkflowRun();
        replay.setId(91L);
        when(workflowRunMapper.getByDedupe(eq(7), eq(11), anyString()))
            .thenReturn(null, replay);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
            .when(workflowRunMapper).insertRun(any());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        assertSame(replay, claim.run());
    }

    @Test
    void claimPinsTheLockedActiveVersionAndEntryNode() {
        stubCanonicalCompilation();
        when(workflowRunMapper.getByDedupe(eq(7), eq(11), anyString()))
            .thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<WorkflowRun>getArgument(0).setId(91L);
            return null;
        }).when(workflowRunMapper).insertRun(any());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        ArgumentCaptor<WorkflowRun> run = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunMapper).insertRun(run.capture());
        assertTrue(claim.started());
        assertEquals(19L, run.getValue().getWorkflowVersionId());
        assertEquals("trigger", run.getValue().getCurrentNodeId());
        assertEquals("queued", run.getValue().getStatus());
        assertEquals(7, run.getValue().getWorkspaceId());
        verify(workflowTriggerOutboxMapper).ensureWorkspaceGate(7);
    }

    @Test
    void invalidScheduleEnrollmentConfigurationReturnsTypedExecutionError() {
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        CanonicalDraft canonical = new CanonicalDraft(
            "Workflow", null, "company", "user", "{}", "{}", new byte[32]);
        when(canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "company", "user", "{}", "{}"))
            .thenReturn(canonical);
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "trigger", List.of(), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(definitionValidator.validate("company", "user", definition))
            .thenReturn(compiled);
        RuleTrigger scheduleTrigger = new RuleTrigger();
        scheduleTrigger.setType("schedule");
        scheduleTrigger.setCadence("daily");
        when(compiled.entryNodeId()).thenReturn("trigger");
        when(compiled.node("trigger")).thenReturn(
            new WorkflowNode.Trigger("trigger", scheduleTrigger));
        when(compiled.enrollmentConditionNodeId()).thenReturn("enrollment");
        when(compiled.node("enrollment")).thenReturn(
            new WorkflowNode.Condition("enrollment", null));
        WorkflowTriggerDispatch.ScheduleTick tick =
            new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "2026-08-02");

        WorkflowExecutionException exception = assertThrows(
            WorkflowExecutionException.class,
            () -> service.scheduleEnrollment(11, tick));

        assertEquals("definition_invalid", exception.code());
        assertTrue(exception.interventionRequired());
    }

    private void stubCanonicalCompilation() {
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        byte[] hash = new byte[32];
        CanonicalDraft canonical = new CanonicalDraft(
            "Workflow", null, "company", "user", "{}", "{}", hash);
        when(canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "company", "user", "{}", "{}"))
            .thenReturn(canonical);
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "trigger", List.of(), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(definitionValidator.validate("company", "user", definition))
            .thenReturn(compiled);
        when(compiled.entryNodeId()).thenReturn("trigger");
        when(compiled.node("trigger")).thenReturn(
            new WorkflowNode.Trigger("trigger", entityTrigger()));
    }

    private static Workflow workflow(String owner) {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(13);
        workflow.setEnabled(true);
        workflow.setRuntimeOwner(owner);
        workflow.setActiveVersionId(19L);
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
        version.setRunAsUserId(17);
        version.setDefinitionJson("{}");
        version.setCanvasJson("{}");
        version.setDefinitionHash(new byte[32]);
        return version;
    }

    private static Rule rule() {
        Rule rule = new Rule();
        rule.setId(13);
        rule.setWorkspaceId(7);
        rule.setRecordType("company");
        rule.setEnabled(true);
        return rule;
    }

    private static RuleTrigger entityTrigger() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        return trigger;
    }

    private String entityDedupeKey(int identity) {
        return new WorkflowDedupeKey().entityChange(
            identity,
            dispatch.recordType(),
            dispatch.recordId(),
            dispatch.event(),
            dispatch.triggerKey(),
            dispatch.occurredAt(),
            null);
    }
}
