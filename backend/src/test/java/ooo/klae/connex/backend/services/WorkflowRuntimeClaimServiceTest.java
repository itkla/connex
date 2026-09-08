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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowDateEnrollment;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEnrollment;
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
    @Mock private WorkflowEnrollmentPolicyService enrollmentPolicyService;
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
            enrollmentPolicyService,
            new WorkflowDedupeKey(Clock.fixed(
                Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC)),
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
    void unpairedWorkflowIdentityHashSuppressesReplayAfterRuleAttachment() {
        stubCanonicalCompilation();
        String primaryKey = entityDedupeKey(13);
        String unpairedWorkflowKey = entityDedupeKey(11);
        when(ruleMapper.getExecutionByDedupe(7, 13, primaryKey))
            .thenReturn(null);
        when(ruleMapper.getExecutionByDedupe(7, 13, unpairedWorkflowKey))
            .thenReturn(new RuleExecution());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        verify(ruleMapper).getExecutionByDedupe(7, 13, primaryKey);
        verify(ruleMapper).getExecutionByDedupe(7, 13, unpairedWorkflowKey);
        verify(workflowRunMapper, never()).insertRun(any());
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
        when(workflowRunMapper.getByDedupe(7, 11, entityDedupeKey(13)))
            .thenReturn(null, replay);
        when(workflowRunMapper.getByDedupe(7, 11, entityDedupeKey(11)))
            .thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
            .when(workflowRunMapper).insertRun(any());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        assertSame(replay, claim.run());
    }

    @Test
    void claimPinsTheLockedActiveVersionEntryNodeAndUtcStartInHonolulu() {
        stubCanonicalCompilation();
        when(workflowRunMapper.getByDedupe(eq(7), eq(11), anyString()))
            .thenReturn(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<WorkflowRun>getArgument(0).setId(91L);
            return null;
        }).when(workflowRunMapper).insertRun(any());

        TimeZone originalTimezone = TimeZone.getDefault();
        WorkflowRuntimeClaimService.CanonicalClaim claim;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            claim = service.claimEntity(11, dispatch);
        } finally {
            TimeZone.setDefault(originalTimezone);
        }

        ArgumentCaptor<WorkflowRun> run = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunMapper).insertRun(run.capture());
        assertTrue(claim.started());
        assertEquals(19L, run.getValue().getWorkflowVersionId());
        assertEquals("trigger", run.getValue().getCurrentNodeId());
        assertEquals("queued", run.getValue().getStatus());
        assertEquals(7, run.getValue().getWorkspaceId());
        assertEquals(entityDedupeKey(13), run.getValue().getDedupeKey());
        assertTrue(run.getValue().getStartedAt().isAfter(
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
        verify(workflowTriggerOutboxMapper).ensureWorkspaceGate(7);
        InOrder lockOrder = inOrder(workflowTriggerOutboxMapper, workflowMapper);
        lockOrder.verify(workflowTriggerOutboxMapper).ensureWorkspaceGate(7);
        lockOrder.verify(workflowMapper).getByIdForUpdate(7, 11);
    }

    @Test
    void automaticEnrollmentRetainsTheSerializedActiveRunBlocker() {
        stubCanonicalCompilation();
        when(compiled.schemaVersion()).thenReturn(2);
        when(enrollmentPolicyService.evaluateLocked(any(), eq(compiled), eq(17)))
            .thenReturn(new WorkflowEnrollmentPolicyService.Decision(
                "active_run_exists", null));

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimEntity(11, dispatch);

        ArgumentCaptor<WorkflowRun> persisted = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunMapper).insertRun(persisted.capture());
        assertTrue(claim.rejected());
        assertEquals("skipped", persisted.getValue().getStatus());
        assertEquals("active_run_exists", persisted.getValue().getStatusReason());
        InOrder order = inOrder(
            workflowTriggerOutboxMapper,
            workflowMapper,
            enrollmentPolicyService,
            workflowRunMapper);
        order.verify(workflowTriggerOutboxMapper).ensureWorkspaceGate(7);
        order.verify(workflowMapper).getByIdForUpdate(7, 11);
        order.verify(enrollmentPolicyService).evaluateLocked(any(), eq(compiled), eq(17));
        order.verify(workflowRunMapper).insertRun(any());
    }

    @Test
    void dateClaimUsesAWorkflowScopedVersionIndependentSourcePeriodKey() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("date");
        trigger.setDateField("expectedCloseDate");
        trigger.setOffsetDays(-30);
        trigger.setLocalTime("09:00");
        trigger.setTimezone("Pacific/Honolulu");
        trigger.setAllowManualRuns(false);
        workflow.setRuntimeGeneration(5L);
        version.setRecordType("deal");
        stubCanonicalCompilation(trigger);
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setId(31L);
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(19L);
        outbox.setWorkflowRuntimeGeneration(5L);
        WorkflowDateEnrollment enrollment = new WorkflowDateEnrollment();
        enrollment.setWorkflowId(11);
        enrollment.setWorkflowVersionId(19L);
        enrollment.setWorkflowRuntimeGeneration(5L);
        enrollment.setRecordId(43);
        enrollment.setDateField("expectedCloseDate");
        enrollment.setSourceDate(LocalDate.of(2027, 3, 31));
        enrollment.setScheduledLocalDate(LocalDate.of(2027, 3, 1));
        enrollment.setDueAt(LocalDateTime.of(2027, 3, 1, 19, 0));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<WorkflowRun>getArgument(0).setId(91L);
            return null;
        }).when(workflowRunMapper).insertRun(any());

        WorkflowRuntimeClaimService.CanonicalClaim claim = service.claimDate(
            outbox, enrollment);

        ArgumentCaptor<WorkflowRun> persisted = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunMapper).insertRun(persisted.capture());
        assertTrue(claim.started());
        assertEquals(
            "date:11:deal:43:expectedCloseDate:2027-03-31",
            persisted.getValue().getDedupeKey());
        assertEquals(LocalDate.of(2027, 3, 31), persisted.getValue().getDateSourceDate());
    }

    @Test
    void durableScheduleClaimChecksThePreUpgradePlaintextLedgerKey() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        stubCanonicalCompilation(trigger);
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setId(31L);
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(19L);
        outbox.setTriggerType("schedule");
        outbox.setTriggerEvent("daily");
        outbox.setTriggerKey("20260803");
        outbox.setRecordType("company");
        RuleExecution legacy = new RuleExecution();
        when(ruleMapper.getExecutionByDedupe(eq(7), eq(13), anyString()))
            .thenAnswer(call -> "41:20260803".equals(call.getArgument(2, String.class))
                ? legacy : null);

        WorkflowRuntimeClaimService.CanonicalClaim claim =
            service.claimOutbox(outbox, 41);

        assertTrue(claim.replayed());
        assertFalse(claim.started());
        assertNull(claim.run());
        verify(ruleMapper).getExecutionByDedupe(7, 13, "41:20260803");
        verify(workflowRunMapper, never()).insertRun(any());
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
        when(definitionValidator.validate(version.getRecordType(), "user", definition))
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

    @Test
    void v2CoolingScheduleUsesTopLevelEnrollmentBeforeItsDirectAction() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        stubCanonicalCompilation(trigger);
        SegmentCondition cooling = new SegmentCondition();
        cooling.setType("predicate");
        cooling.setKey("cooling");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(cooling));
        when(compiled.schemaVersion()).thenReturn(2);
        when(compiled.enrollment()).thenReturn(
            new WorkflowEnrollment(condition, true, 43_200));
        when(compiled.enrollmentConditionNodeId()).thenReturn(null);
        workflow.setRuntimeGeneration(5L);
        WorkflowTriggerOutbox outbox = new WorkflowTriggerOutbox();
        outbox.setWorkspaceId(7);
        outbox.setWorkflowId(11);
        outbox.setWorkflowVersionId(19L);
        outbox.setWorkflowRuntimeGeneration(5L);
        outbox.setTriggerType("schedule");
        outbox.setTriggerEvent("daily");
        outbox.setTriggerKey("20260803");
        outbox.setRecordType("company");

        WorkflowRuntimeClaimService.ScheduleEnrollment enrollment =
            service.outboxScheduleEnrollment(outbox);

        assertSame(condition, enrollment.condition());
        assertEquals(17, enrollment.conditionActorId());
        verify(compiled, never()).node(null);
    }

    @Test
    void v1ScheduleRetainsItsImmediateConditionEnrollment() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
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
        when(compiled.schemaVersion()).thenReturn(1);
        when(compiled.entryNodeId()).thenReturn("trigger");
        when(compiled.node("trigger")).thenReturn(
            new WorkflowNode.Trigger("trigger", trigger));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        when(compiled.enrollmentConditionNodeId()).thenReturn("enrollment");
        when(compiled.node("enrollment")).thenReturn(
            new WorkflowNode.Condition("enrollment", condition));
        WorkflowTriggerDispatch.ScheduleTick tick =
            new WorkflowTriggerDispatch.ScheduleTick(7, "daily", "20260803");

        WorkflowRuntimeClaimService.ScheduleEnrollment enrollment =
            service.scheduleEnrollment(11, tick);

        assertSame(condition, enrollment.condition());
    }

    private void stubCanonicalCompilation() {
        stubCanonicalCompilation(entityTrigger());
    }

    private void stubCanonicalCompilation(RuleTrigger trigger) {
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(version);
        String recordType = version.getRecordType();
        byte[] hash = new byte[32];
        CanonicalDraft canonical = new CanonicalDraft(
            "Workflow", null, recordType, "user", "{}", "{}", hash);
        when(canonicalizer.canonicalizeDraftJson(
            "Workflow", null, recordType, "user", "{}", "{}"))
            .thenReturn(canonical);
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "trigger", List.of(), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(definitionValidator.validate(recordType, "user", definition))
            .thenReturn(compiled);
        when(compiled.entryNodeId()).thenReturn("trigger");
        when(compiled.node("trigger")).thenReturn(
            new WorkflowNode.Trigger("trigger", trigger));
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
