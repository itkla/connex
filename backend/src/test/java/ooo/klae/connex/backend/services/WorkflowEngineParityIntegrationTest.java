package ooo.klae.connex.backend.services;

import static ooo.klae.connex.backend.services.WorkflowParityTestSupport.assertEffectsParity;
import static ooo.klae.connex.backend.services.WorkflowParityTestSupport.assertParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@Import(WorkflowEngineParityIntegrationTest.FixedDedupeConfiguration.class)
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=true",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowEngineParityIntegrationTest extends AbstractServiceTest {

    private static final int MAX_SCHEDULER_DRAIN_CYCLES = 32;
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T12:34:00Z");
    private static final int NORMALIZED_SUBJECT_ID = 0;

    @Autowired private RuleService ruleService;
    @Autowired private WorkflowRuntimeService workflowRuntimeService;
    @Autowired private WorkflowRuntimeOwnershipService ownershipService;
    @Autowired private WorkflowRuntimeClaimTransaction claimTransaction;
    @Autowired private WorkflowTriggerOutboxWorker outboxWorker;
    @Autowired private WorkflowRunWorker runWorker;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private ShareMapper shareMapper;
    @Autowired private LegacyWorkflowBackfillTransaction backfillTransaction;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoSpyBean private RuleActionExecutor actionExecutor;

    private final List<Integer> createdCompanyIds = new ArrayList<>();
    private final List<Integer> createdPersonIds = new ArrayList<>();
    private final List<Integer> createdDealIds = new ArrayList<>();
    private final List<Integer> createdPipelineIds = new ArrayList<>();
    private final List<Integer> createdTagIds = new ArrayList<>();
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdForeignPersonIds = new ArrayList<>();
    private final List<Integer> createdForeignWorkspaceIds = new ArrayList<>();

    @Test
    void p1CompanyUpdatedAddTagHasParity() {
        Company legacyCompany = createCompany();
        Company canonicalCompany = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "company", "company.updated", List.of(addTag(tag.getId())), null, null, null,
            "user");

        PairedSnapshots snapshots = pairedSnapshots(
            rule,
            subject("company", legacyCompany.getId()),
            subject("company", canonicalCompany.getId()),
            "company.updated");

        assertParity(snapshots.legacy(), snapshots.canonical());
        assertEquals(List.of(tag.getId()), snapshots.legacy().tagIds());
    }

    @Test
    void p2UnlistedEventProducesNoEffectOrLedgerRow() {
        Company legacyCompany = createCompany();
        Company canonicalCompany = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "company", "company.created", List.of(addTag(tag.getId())), null, null, null,
            "user");

        PairedSnapshots snapshots = pairedSnapshots(
            rule,
            subject("company", legacyCompany.getId()),
            subject("company", canonicalCompany.getId()),
            "company.updated");

        assertParity(snapshots.legacy(), snapshots.canonical());
        assertEquals(new EffectSnapshot.RunOutcome(List.of(), 0), snapshots.legacy().runOutcome());
        assertEquals(0, snapshots.legacy().actionInvocationCount());
    }

    @Test
    void p3DealFieldConditionMatchesAndSuppressesTheNoBranch() {
        Pipeline pipeline = createPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal legacyMatch = createDeal(pipeline, stage, createCompany());
        Deal canonicalMatch = createDeal(pipeline, stage, createCompany());
        Deal legacyMiss = createDeal(pipeline, stage, createCompany());
        Deal canonicalMiss = createDeal(pipeline, stage, createCompany());
        legacyMiss.setValue(new BigDecimal("100.00"));
        canonicalMiss.setValue(new BigDecimal("100.00"));
        dealMapper.updateValueAndSource(
            workspace.getId(), legacyMiss.getId(), legacyMiss.getValue(), "manual");
        dealMapper.updateValueAndSource(
            workspace.getId(), canonicalMiss.getId(), canonicalMiss.getValue(), "manual");
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "deal",
            "deal.updated",
            List.of(addTag(tag.getId())),
            fieldCondition("value", "gte", "500"),
            null,
            null,
            "user");
        clearInvocations(actionExecutor);

        dispatchEntity(subject("deal", legacyMatch.getId()), "deal.updated", "p3-match-left");
        dispatchEntity(subject("deal", legacyMiss.getId()), "deal.updated", "p3-miss-left");
        drainSchedulerWork();
        EffectSnapshot legacyMatched = snapshot(subject("deal", legacyMatch.getId()), rule);
        EffectSnapshot legacyMissed = snapshot(subject("deal", legacyMiss.getId()), rule);
        cutOver(rule.getId());
        dispatchEntity(
            subject("deal", canonicalMatch.getId()), "deal.updated", "p3-match-right");
        dispatchEntity(
            subject("deal", canonicalMiss.getId()), "deal.updated", "p3-miss-right");
        drainSchedulerWork();
        EffectSnapshot canonicalMatched = snapshot(subject("deal", canonicalMatch.getId()), rule);
        EffectSnapshot canonicalMissed = snapshot(subject("deal", canonicalMiss.getId()), rule);

        assertParity(legacyMatched, canonicalMatched);
        assertEffectsParity(legacyMissed, canonicalMissed);
        assertEquals(List.of("skipped"), legacyMissed.runOutcome().statuses());
        assertEquals(List.of("succeeded"), canonicalMissed.runOutcome().statuses());
        assertEquals(0, legacyMissed.actionInvocationCount());
        assertEquals(0, canonicalMissed.actionInvocationCount());
    }

    @Test
    void p4TargetStageFiresOnlyForTheMatchingStage() {
        Pipeline pipeline = createPipeline();
        Stage matching = newStage(pipeline, 0);
        Stage other = newStage(pipeline, 1);
        Deal legacyMatch = createDeal(pipeline, matching, createCompany());
        Deal canonicalMatch = createDeal(pipeline, matching, createCompany());
        Deal legacyMiss = createDeal(pipeline, other, createCompany());
        Deal canonicalMiss = createDeal(pipeline, other, createCompany());
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "deal",
            "deal.stage_changed",
            List.of(addTag(tag.getId())),
            null,
            matching.getId(),
            null,
            "user");
        clearInvocations(actionExecutor);

        dispatchEntity(
            subject("deal", legacyMatch.getId()), "deal.stage_changed", "p4-match-left");
        dispatchEntity(
            subject("deal", legacyMiss.getId()), "deal.stage_changed", "p4-miss-left");
        drainSchedulerWork();
        EffectSnapshot legacyMatched = snapshot(subject("deal", legacyMatch.getId()), rule);
        EffectSnapshot legacyMissed = snapshot(subject("deal", legacyMiss.getId()), rule);
        cutOver(rule.getId());
        dispatchEntity(
            subject("deal", canonicalMatch.getId()), "deal.stage_changed", "p4-match-right");
        dispatchEntity(
            subject("deal", canonicalMiss.getId()), "deal.stage_changed", "p4-miss-right");
        drainSchedulerWork();
        EffectSnapshot canonicalMatched = snapshot(subject("deal", canonicalMatch.getId()), rule);
        EffectSnapshot canonicalMissed = snapshot(subject("deal", canonicalMiss.getId()), rule);

        assertParity(legacyMatched, canonicalMatched);
        assertParity(legacyMissed, canonicalMissed);
        assertEquals(0, legacyMissed.runOutcome().rowCount());
    }

    /** Both engines suppress a suspended contact; their documented terminal statuses differ. */
    @Test
    void p5PersonSubjectFiresAndSuspendedPersonFailsClosed() {
        Person legacyActive = createPerson(createCompany());
        Person canonicalActive = createPerson(createCompany());
        Person legacySuspended = createPerson(createCompany());
        Person canonicalSuspended = createPerson(createCompany());
        personMapper.updateProcessingRestrictions(
            workspace.getId(), legacySuspended.getId(), true, false);
        personMapper.updateProcessingRestrictions(
            workspace.getId(), canonicalSuspended.getId(), true, false);
        RuleDto rule = entityRule(
            "person", "person.updated", List.of(notifyAction("Person parity")), null,
            null, null, "user");
        clearInvocations(actionExecutor);

        dispatchEntity(subject("person", legacyActive.getId()), "person.updated", "p5-live-left");
        dispatchEntity(
            subject("person", legacySuspended.getId()), "person.updated", "p5-stop-left");
        drainSchedulerWork();
        EffectSnapshot legacyLive = snapshot(subject("person", legacyActive.getId()), rule);
        EffectSnapshot legacyStopped = snapshot(
            subject("person", legacySuspended.getId()), rule);
        cutOver(rule.getId());
        dispatchEntity(
            subject("person", canonicalActive.getId()), "person.updated", "p5-live-right");
        dispatchEntity(
            subject("person", canonicalSuspended.getId()), "person.updated", "p5-stop-right");
        drainSchedulerWork();
        EffectSnapshot canonicalLive = snapshot(subject("person", canonicalActive.getId()), rule);
        EffectSnapshot canonicalStopped = snapshot(
            subject("person", canonicalSuspended.getId()), rule);

        assertParity(legacyLive, canonicalLive);
        assertEffectsParity(legacyStopped, canonicalStopped);
        assertEquals(List.of("failed"), legacyStopped.runOutcome().statuses());
        assertEquals(List.of("intervention_required"), canonicalStopped.runOutcome().statuses());
        assertEquals("record_unavailable", canonicalFailureCode(rule, canonicalSuspended.getId()));
    }

    /** A missing document is effect-equivalent but reaches each engine's documented failure ledger. */
    @Test
    void p6DocumentTaskAttachesToParentDealAndMissingDocumentFailsClosed() {
        Pipeline pipeline = createPipeline();
        Stage stage = newStage(pipeline, 0);
        DocumentSubject legacyDocument = createDocumentSubject(pipeline, stage);
        DocumentSubject canonicalDocument = createDocumentSubject(pipeline, stage);
        DocumentSubject legacyMissing = createDocumentSubject(pipeline, stage);
        DocumentSubject canonicalMissing = createDocumentSubject(pipeline, stage);
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle("Countersign parity");
        action.setDueInDays(4);
        RuleDto rule = entityRule(
            "document", "document.finalized", List.of(action), null, null, null, "user");
        assertEquals(1, jdbcTemplate.update(
            "DELETE FROM deal_document WHERE workspace_id = ? AND id = ?",
            workspace.getId(), legacyMissing.documentId()));
        assertEquals(1, jdbcTemplate.update(
            "DELETE FROM deal_document WHERE workspace_id = ? AND id = ?",
            workspace.getId(), canonicalMissing.documentId()));
        clearInvocations(actionExecutor);

        Subject legacyLive = subject(
            "document", legacyDocument.documentId(), legacyDocument.dealId());
        Subject canonicalLive = subject(
            "document", canonicalDocument.documentId(), canonicalDocument.dealId());
        Subject legacyGone = subject(
            "document", legacyMissing.documentId(), legacyMissing.dealId());
        Subject canonicalGone = subject(
            "document", canonicalMissing.documentId(), canonicalMissing.dealId());
        dispatchEntity(legacyLive, "document.finalized", "p6-live-left");
        dispatchEntity(legacyGone, "document.finalized", "p6-gone-left");
        drainSchedulerWork();
        EffectSnapshot legacyAttached = snapshot(legacyLive, rule);
        EffectSnapshot legacyFailed = snapshot(legacyGone, rule);
        cutOver(rule.getId());
        dispatchEntity(canonicalLive, "document.finalized", "p6-live-right");
        dispatchEntity(canonicalGone, "document.finalized", "p6-gone-right");
        drainSchedulerWork();
        EffectSnapshot canonicalAttached = snapshot(canonicalLive, rule);
        EffectSnapshot canonicalFailed = snapshot(canonicalGone, rule);

        assertParity(legacyAttached, canonicalAttached);
        assertEquals(1, legacyAttached.tasks().size());
        assertEquals(NORMALIZED_SUBJECT_ID, legacyAttached.tasks().getFirst().dealId());
        assertEquals(Integer.valueOf(stage.getId()), legacyAttached.dealStageId());
        assertEffectsParity(legacyFailed, canonicalFailed);
        assertEquals(List.of("partial"), legacyFailed.runOutcome().statuses());
        assertEquals(List.of("intervention_required"), canonicalFailed.runOutcome().statuses());
        assertEquals("record_unavailable", canonicalFailureCode(rule, canonicalMissing.documentId()));
    }

    @Test
    void p7ThrottleCollapsesRepeatsWithinTheWindow() {
        Company legacyCompany = createCompany();
        Company canonicalCompany = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "company", "company.updated", List.of(addTag(tag.getId())), null, null, 60,
            "user");
        clearInvocations(actionExecutor);

        Subject legacy = subject("company", legacyCompany.getId());
        dispatchEntity(legacy, "company.updated", "p7-left-one", OCCURRED_AT);
        dispatchEntity(legacy, "company.updated", "p7-left-two", OCCURRED_AT.plusSeconds(30));
        drainSchedulerWork();
        EffectSnapshot legacySnapshot = snapshot(legacy, rule);
        cutOver(rule.getId());
        Subject canonical = subject("company", canonicalCompany.getId());
        dispatchEntity(canonical, "company.updated", "p7-right-one", OCCURRED_AT);
        dispatchEntity(
            canonical, "company.updated", "p7-right-two", OCCURRED_AT.plusSeconds(30));
        drainSchedulerWork();
        EffectSnapshot canonicalSnapshot = snapshot(canonical, rule);

        assertParity(legacySnapshot, canonicalSnapshot);
        assertEquals(1, legacySnapshot.runOutcome().rowCount());
        assertEquals(1, legacySnapshot.actionInvocationCount());
    }

    @Test
    void p8SystemExecutionUsesTheSystemActorWithParity() {
        Company legacyCompany = createCompany();
        Company canonicalCompany = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityRule(
            "company", "company.updated", List.of(addTag(tag.getId())), null, null, null,
            "system");

        PairedSnapshots snapshots = pairedSnapshots(
            rule,
            subject("company", legacyCompany.getId()),
            subject("company", canonicalCompany.getId()),
            "company.updated");

        assertParity(snapshots.legacy(), snapshots.canonical());
    }

    @Test
    void p9CreateTaskActionHasParity() {
        DealPair pair = dealPair();
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle("Task parity");
        action.setDueInDays(5);
        assertActionParity(
            entityRule("deal", "deal.updated", List.of(action), null, null, null, "user"),
            subject("deal", pair.legacy().getId()),
            subject("deal", pair.canonical().getId()),
            "deal.updated");
    }

    @Test
    void p9LogActivityActionHasParity() {
        DealPair pair = dealPair();
        RuleAction action = new RuleAction();
        action.setType("log_activity");
        action.setActivityType("call");
        action.setTitle("Activity parity");
        action.setBody("Activity body");
        assertActionParity(
            entityRule("deal", "deal.updated", List.of(action), null, null, null, "user"),
            subject("deal", pair.legacy().getId()),
            subject("deal", pair.canonical().getId()),
            "deal.updated");
    }

    @Test
    void p9AddTagActionHasParity() {
        Company legacy = createCompany();
        Company canonical = createCompany();
        Tag tag = createTag();
        assertActionParity(
            entityRule(
                "company", "company.updated", List.of(addTag(tag.getId())), null,
                null, null, "user"),
            subject("company", legacy.getId()),
            subject("company", canonical.getId()),
            "company.updated");
    }

    @Test
    void p9RemoveTagActionHasParity() {
        Company legacy = createCompany();
        Company canonical = createCompany();
        Tag tag = createTag();
        companyMapper.addTag(workspace.getId(), legacy.getId(), tag.getId());
        companyMapper.addTag(workspace.getId(), canonical.getId(), tag.getId());
        RuleAction action = new RuleAction();
        action.setType("remove_tag");
        action.setTagId(tag.getId());
        assertActionParity(
            entityRule(
                "company", "company.updated", List.of(action), null, null, null, "user"),
            subject("company", legacy.getId()),
            subject("company", canonical.getId()),
            "company.updated");
    }

    @Test
    void p9CreateNoteActionHasParity() {
        DealPair pair = dealPair();
        RuleAction action = new RuleAction();
        action.setType("create_note");
        action.setBody("Note parity");
        assertActionParity(
            entityRule("deal", "deal.updated", List.of(action), null, null, null, "user"),
            subject("deal", pair.legacy().getId()),
            subject("deal", pair.canonical().getId()),
            "deal.updated");
    }

    @Test
    void p9AssignOwnerActionHasParity() {
        DealPair pair = dealPair();
        User owner = createUser();
        RuleAction action = new RuleAction();
        action.setType("assign_owner");
        action.setTargetUserId(owner.getId());
        assertActionParity(
            entityRule("deal", "deal.updated", List.of(action), null, null, null, "user"),
            subject("deal", pair.legacy().getId()),
            subject("deal", pair.canonical().getId()),
            "deal.updated");
    }

    @Test
    void p9SetResponseDueActionHasParity() {
        Person legacy = createPerson(createCompany());
        Person canonical = createPerson(createCompany());
        RuleAction action = new RuleAction();
        action.setType("set_response_due");
        action.setDueInHours(4);
        PairedSnapshots snapshots = pairedSnapshots(
            entityRule(
                "person", "person.updated", List.of(action), null, null, null, "user"),
            subject("person", legacy.getId()),
            subject("person", canonical.getId()),
            "person.updated");

        assertParity(snapshots.legacy(), snapshots.canonical());
        assertTrue(snapshots.legacy().responseDueSet());
    }

    @Test
    void p9ChangeStageActionHasParity() {
        Pipeline pipeline = createPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Deal legacy = createDeal(pipeline, from, createCompany());
        Deal canonical = createDeal(pipeline, from, createCompany());
        RuleAction action = new RuleAction();
        action.setType("change_stage");
        action.setTargetStageId(to.getId());
        PairedSnapshots snapshots = pairedSnapshots(
            entityRule("deal", "deal.updated", List.of(action), null, null, null, "user"),
            subject("deal", legacy.getId()),
            subject("deal", canonical.getId()),
            "deal.updated");

        assertParity(snapshots.legacy(), snapshots.canonical());
        assertEquals(to.getId(), snapshots.legacy().dealStageId());
    }

    @Test
    void p9NotifyActionHasParity() {
        Company legacy = createCompany();
        Company canonical = createCompany();
        assertActionParity(
            entityRule(
                "company", "company.updated", List.of(notifyAction("Notify parity")), null,
                null, null, "user"),
            subject("company", legacy.getId()),
            subject("company", canonical.getId()),
            "company.updated");
    }

    @Test
    void p10ScheduleDailyMatchesRejectsWrongCadenceAndIsBucketIdempotent() {
        String industry = "Parity-" + unique();
        Company legacyCompany = createCompany(industry);
        Tag tag = createTag();
        RuleDto rule = scheduleRule(
            List.of(addTag(tag.getId())), fieldCondition("industry", "equals", industry),
            "user");
        clearInvocations(actionExecutor);

        dispatchSchedule("weekly", "p10-wrong-left");
        dispatchSchedule("daily", "p10-daily-left");
        dispatchSchedule("daily", "p10-daily-left");
        EffectSnapshot legacy = snapshot(subject("company", legacyCompany.getId()), rule);
        cutOver(rule.getId());
        Company canonicalCompany = createCompany(industry);
        dispatchSchedule("weekly", "p10-wrong-right");
        dispatchSchedule("daily", "p10-daily-right");
        dispatchSchedule("daily", "p10-daily-right");
        EffectSnapshot canonical = snapshot(subject("company", canonicalCompany.getId()), rule);

        assertParity(legacy, canonical);
        assertEquals(1, legacy.runOutcome().rowCount());
        assertEquals(1, legacy.actionInvocationCount());
    }

    @Nested
    class KnownDivergences {

        /** Legacy continues after an action failure; canonical stops before the guarded node effect. */
        @Test
        void d1SecondActionFailureContinuesOnlyOnLegacy() {
            DealPair pair = dealPair();
            Tag deleted = createTag();
            RuleAction first = noteAction("First note");
            RuleAction failing = addTag(deleted.getId());
            RuleAction third = noteAction("Third note");
            RuleDto rule = entityRule(
                "deal", "deal.updated", List.of(first, failing, third), null, null, null,
                "user");
            tagMapper.delete(workspace.getId(), deleted.getId());
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("deal", pair.legacy().getId());
            dispatchEntity(legacySubject, "deal.updated", "d1-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("deal", pair.canonical().getId());
            dispatchEntity(canonicalSubject, "deal.updated", "d1-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEquals(List.of("partial"), legacy.runOutcome().statuses());
            assertEquals(List.of("intervention_required"), canonical.runOutcome().statuses());
            assertEquals(2, legacy.notes().size());
            assertEquals(1, canonical.notes().size());
            assertEquals(3, legacy.actionInvocationCount());
            assertEquals(1, canonical.actionInvocationCount());
            assertEquals("action_tag_unavailable", canonicalFailureCode(rule, pair.canonical().getId()));
        }

        /** Legacy notification dedupe overwrites within a fire; canonical dedupes per action node. */
        @Test
        void d2TwoNotificationsProduceOneLegacyRowAndTwoCanonicalRows() {
            Company legacyCompany = createCompany();
            Company canonicalCompany = createCompany();
            RuleDto rule = entityRule(
                "company",
                "company.updated",
                List.of(notifyAction("First notification"), notifyAction("Second notification")),
                null,
                null,
                null,
                "user");
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("company", legacyCompany.getId());
            dispatchEntity(legacySubject, "company.updated", "d2-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("company", canonicalCompany.getId());
            dispatchEntity(canonicalSubject, "company.updated", "d2-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEquals(1, legacy.notifications().size());
            assertEquals("Second notification", legacy.notifications().getFirst().title());
            assertEquals(
                List.of("First notification", "Second notification"),
                canonical.notifications().stream()
                    .map(EffectSnapshot.NotificationEffect::title)
                    .sorted()
                    .toList());
            assertEquals(2, legacy.actionInvocationCount());
            assertEquals(2, canonical.actionInvocationCount());
        }

        /** Canonical validates the deleted tag before invoking the shared action executor. */
        @Test
        void d3DeletedAddTagReferenceIsPreflightedOnlyByCanonical() {
            Company legacyCompany = createCompany();
            Company canonicalCompany = createCompany();
            Tag deleted = createTag();
            RuleDto rule = entityRule(
                "company", "company.updated", List.of(addTag(deleted.getId())), null,
                null, null, "user");
            tagMapper.delete(workspace.getId(), deleted.getId());
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("company", legacyCompany.getId());
            dispatchEntity(legacySubject, "company.updated", "d3-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("company", canonicalCompany.getId());
            dispatchEntity(canonicalSubject, "company.updated", "d3-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEquals(List.of("partial"), legacy.runOutcome().statuses());
            assertEquals(List.of("intervention_required"), canonical.runOutcome().statuses());
            assertEquals(1, legacy.actionInvocationCount());
            assertEquals(0, canonical.actionInvocationCount());
            assertEquals("action_tag_unavailable", canonicalFailureCode(rule, canonicalCompany.getId()));
        }

        /** Canonical rejects an inactive assignment target before invoking the shared executor. */
        @Test
        void d4DepartedOwnerTargetIsPreflightedOnlyByCanonical() {
            DealPair pair = dealPair();
            User departed = createUser();
            RuleAction action = new RuleAction();
            action.setType("assign_owner");
            action.setTargetUserId(departed.getId());
            RuleDto rule = entityRule(
                "deal", "deal.updated", List.of(action), null, null, null, "user");
            workspaceMapper.removeMember(workspace.getId(), departed.getId());
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("deal", pair.legacy().getId());
            dispatchEntity(legacySubject, "deal.updated", "d4-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("deal", pair.canonical().getId());
            dispatchEntity(canonicalSubject, "deal.updated", "d4-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEquals(List.of("partial"), legacy.runOutcome().statuses());
            assertEquals(List.of("intervention_required"), canonical.runOutcome().statuses());
            assertEquals(1, legacy.actionInvocationCount());
            assertEquals(0, canonical.actionInvocationCount());
            assertEquals(
                "action_target_member_unavailable",
                canonicalFailureCode(rule, pair.canonical().getId()));
        }

        /** The test seam caps canonical enrollment at 128 while legacy enumerates every match. */
        @Test
        void d5ScheduleEnrollmentAboveTheBoundRunsOnlyOnLegacy() {
            String industry = "Overflow-" + unique();
            List<Company> companies = new ArrayList<>();
            for (int index = 0; index < 129; index++) {
                companies.add(createCompany(industry));
            }
            Tag tag = createTag();
            RuleDto rule = scheduleRule(
                List.of(addTag(tag.getId())), fieldCondition("industry", "equals", industry),
                "user");
            clearInvocations(actionExecutor);

            WorkflowDispatchResult legacyResult = dispatchSchedule("daily", "d5-left");
            assertEquals(0, legacyResult.candidates());
            assertEquals(129, matchedExecutionCount(rule.getId()));
            assertEquals(129, actionInvocationCount("company", companies));
            cutOver(rule.getId());
            clearInvocations(actionExecutor);

            WorkflowDispatchResult canonicalResult = dispatchSchedule("daily", "d5-right");

            assertEquals(1, canonicalResult.candidates());
            assertEquals(0, canonicalResult.started());
            assertEquals(1, canonicalResult.rejected());
            assertEquals(0, workflowRunCount(rule.getId()));
            assertEquals(0, actionInvocationCount("company", companies));
        }

        /** Legacy records a skipped branch while canonical records successful traversal to End. */
        @Test
        void d6WhenNotMatchedUsesDifferentTerminalStatuses() {
            Company legacyCompany = createCompany();
            Company canonicalCompany = createCompany();
            Tag tag = createTag();
            RuleDto rule = entityRule(
                "company",
                "company.updated",
                List.of(addTag(tag.getId())),
                fieldCondition("industry", "equals", "Absent-" + unique()),
                null,
                null,
                "user");
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("company", legacyCompany.getId());
            dispatchEntity(legacySubject, "company.updated", "d6-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("company", canonicalCompany.getId());
            dispatchEntity(canonicalSubject, "company.updated", "d6-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEffectsParity(legacy, canonical);
            assertEquals(List.of("skipped"), legacy.runOutcome().statuses());
            assertEquals(List.of("succeeded"), canonical.runOutcome().statuses());
            assertEquals(0, legacy.actionInvocationCount());
            assertEquals(0, canonical.actionInvocationCount());
        }

        /** Legacy accepts a same-organization shared contact; canonical requires workspace ownership. */
        @Test
        void d7SharedPersonVisibilityDiffersAcrossEngines() {
            Workspace ownerWorkspace = createForeignWorkspace();
            Person legacyPerson = createForeignPerson(ownerWorkspace);
            Person canonicalPerson = createForeignPerson(ownerWorkspace);
            assertEquals(1, shareMapper.sharePerson(
                legacyPerson.getId(),
                ownerWorkspace.getId(),
                workspace.getId(),
                currentUser.getId(),
                false));
            assertEquals(1, shareMapper.sharePerson(
                canonicalPerson.getId(),
                ownerWorkspace.getId(),
                workspace.getId(),
                currentUser.getId(),
                false));
            RuleDto rule = entityRule(
                "person", "person.updated", List.of(notifyAction("Shared person")), null,
                null, null, "user");
            clearInvocations(actionExecutor);

            Subject legacySubject = subject("person", legacyPerson.getId());
            dispatchEntity(legacySubject, "person.updated", "d7-left");
            drainSchedulerWork();
            EffectSnapshot legacy = snapshot(legacySubject, rule);
            cutOver(rule.getId());
            Subject canonicalSubject = subject("person", canonicalPerson.getId());
            dispatchEntity(canonicalSubject, "person.updated", "d7-right");
            drainSchedulerWork();
            EffectSnapshot canonical = snapshot(canonicalSubject, rule);

            assertEquals(List.of("succeeded"), legacy.runOutcome().statuses());
            assertEquals(List.of("intervention_required"), canonical.runOutcome().statuses());
            assertEquals(1, legacy.notifications().size());
            assertTrue(canonical.notifications().isEmpty());
            assertEquals(1, legacy.actionInvocationCount());
            assertEquals(0, canonical.actionInvocationCount());
            assertEquals("record_unavailable", canonicalFailureCode(rule, canonicalPerson.getId()));
        }

        /** Pre-existing null-condition schedules stay inert and are hard cutover blockers. */
        @Test
        void d8NullConditionScheduleCannotCutOverToCanonical() throws Exception {
            RuleTrigger trigger = new RuleTrigger();
            trigger.setType("schedule");
            trigger.setCadence("daily");
            Rule rule = insertUnpairedRule(
                "Null schedule " + unique(),
                "company",
                trigger,
                List.of(notifyAction("Null condition")));
            backfillTransaction.backfillWorkspace(null, workspace.getId());
            Workflow workflow = requireWorkflow(rule.getId());

            WorkflowDefinitionValidationException failure = assertThrows(
                WorkflowDefinitionValidationException.class,
                () -> ownershipService.cutOverToCanonical(
                    workflow.getId(), requireActiveVersion(workflow)));

            assertEquals(
                WorkflowDiagnosticCode.SCHEDULE_ENROLLMENT_CONDITION_REQUIRED,
                failure.diagnostic().code());
            assertEquals("legacy", requireWorkflow(rule.getId()).getRuntimeOwner());
            Rule persistedRule = ruleMapper.getById(workspace.getId(), rule.getId());
            if (persistedRule == null) {
                throw new AssertionError("Legacy schedule rule disappeared during cutover");
            }
            assertTrue(persistedRule.isEnabled());
        }
    }

    private PairedSnapshots pairedSnapshots(
            RuleDto rule, Subject legacySubject, Subject canonicalSubject, String event) {
        clearInvocations(actionExecutor);
        dispatchEntity(legacySubject, event, "legacy-" + unique());
        drainSchedulerWork();
        EffectSnapshot legacy = snapshot(legacySubject, rule);
        cutOver(rule.getId());
        dispatchEntity(canonicalSubject, event, "canonical-" + unique());
        drainSchedulerWork();
        return new PairedSnapshots(legacy, snapshot(canonicalSubject, rule));
    }

    private void assertActionParity(
            RuleDto rule, Subject legacySubject, Subject canonicalSubject, String event) {
        PairedSnapshots snapshots = pairedSnapshots(rule, legacySubject, canonicalSubject, event);
        assertParity(snapshots.legacy(), snapshots.canonical());
        assertEquals(1, snapshots.legacy().actionInvocationCount());
    }

    private RuleDto entityRule(
            String recordType,
            String event,
            List<RuleAction> actions,
            SegmentDefinition condition,
            Integer targetStageId,
            Integer throttleMinutes,
            String executionMode) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(event));
        trigger.setTargetStageId(targetStageId);
        trigger.setThrottleMinutes(throttleMinutes);
        return rule(recordType, trigger, actions, condition, executionMode);
    }

    private RuleDto scheduleRule(
            List<RuleAction> actions, SegmentDefinition condition, String executionMode) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        return rule("company", trigger, actions, condition, executionMode);
    }

    private RuleDto rule(
            String recordType,
            RuleTrigger trigger,
            List<RuleAction> actions,
            SegmentDefinition condition,
            String executionMode) {
        RuleRequest request = new RuleRequest();
        request.setName("Parity " + unique());
        request.setEnabled(true);
        request.setRecordType(recordType);
        request.setTrigger(trigger);
        request.setCondition(condition);
        request.setActions(actions);
        request.setExecutionMode(executionMode);
        return ruleService.create(request);
    }

    private Rule insertUnpairedRule(
            String name,
            String recordType,
            RuleTrigger trigger,
            List<RuleAction> actions) throws Exception {
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName(name);
        rule.setEnabled(true);
        rule.setRecordType(recordType);
        rule.setTriggerType(trigger.getType());
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setConditionJson(null);
        rule.setActionsJson(objectMapper.writeValueAsString(actions));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(currentUser.getId());
        rule.setCreatedById(currentUser.getId());
        ruleMapper.insert(rule);
        assertTrue(rule.getId() > 0);
        return rule;
    }

    private void cutOver(int ruleId) {
        Workflow workflow = requireWorkflow(ruleId);
        ownershipService.cutOverToCanonical(
            workflow.getId(), requireActiveVersion(workflow));
    }

    private Workflow requireWorkflow(int ruleId) {
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        if (workflow == null) {
            throw new AssertionError("Paired workflow is unavailable for rule " + ruleId);
        }
        return workflow;
    }

    private static long requireActiveVersion(Workflow workflow) {
        Long activeVersionId = workflow.getActiveVersionId();
        if (activeVersionId == null) {
            throw new AssertionError("Paired workflow has no active version");
        }
        return activeVersionId;
    }

    private WorkflowDispatchResult dispatchEntity(
            Subject subject, String event, String triggerKey) {
        return dispatchEntity(subject, event, triggerKey, OCCURRED_AT);
    }

    private WorkflowDispatchResult dispatchEntity(
            Subject subject, String event, String triggerKey, Instant occurredAt) {
        return workflowRuntimeService.dispatch(new WorkflowTriggerDispatch.EntityChange(
            workspace.getId(),
            subject.recordType(),
            subject.recordId(),
            event,
            triggerKey,
            occurredAt));
    }

    private WorkflowDispatchResult dispatchSchedule(String cadence, String bucketKey) {
        WorkflowDispatchResult result = workflowRuntimeService.dispatch(
            new WorkflowTriggerDispatch.ScheduleTick(workspace.getId(), cadence, bucketKey));
        drainSchedulerWork();
        return result;
    }

    private EffectSnapshot snapshot(Subject subject, RuleDto rule) {
        Workflow workflow = requireWorkflow(rule.getId());
        List<Integer> tagIds = switch (subject.recordType()) {
            case "company" -> tagMapper.getTagsByCompanyId(
                workspace.getId(), subject.recordId()).stream().map(Tag::getId).sorted().toList();
            case "person" -> tagMapper.getTagsByPersonId(
                workspace.getId(), subject.recordId()).stream().map(Tag::getId).sorted().toList();
            case "deal" -> tagMapper.getTagsByDealId(
                workspace.getId(), subject.recordId()).stream().map(Tag::getId).sorted().toList();
            default -> List.of();
        };
        int attachmentDealId = subject.attachmentDealId() == null
            ? subject.recordId() : subject.attachmentDealId();
        List<Task> tasks = switch (subject.recordType()) {
            case "person" -> taskMapper.getTasksByPersonId(workspace.getId(), subject.recordId());
            case "deal", "document" -> taskMapper.getTasksByDealId(
                workspace.getId(), attachmentDealId);
            default -> List.of();
        };
        LocalDate today = LocalDate.now();
        List<EffectSnapshot.TaskEffect> taskEffects = tasks.stream()
            .map(task -> new EffectSnapshot.TaskEffect(
                task.getDescription(),
                task.getAssignedTo() == null ? null : task.getAssignedTo().getId(),
                dueDateOffset(task.getDueDate(), today),
                normalizedPersonId(task.getPerson(), subject),
                normalizedDealId(task.getDeal(), attachmentDealId)))
            .sorted(Comparator.comparing(EffectSnapshot.TaskEffect::toString))
            .toList();
        List<Activity> activities = switch (subject.recordType()) {
            case "person" -> activityMapper.getActivitiesByPersonId(
                workspace.getId(), subject.recordId());
            case "deal", "document" -> activityMapper.getActivitiesByDealId(
                workspace.getId(), attachmentDealId);
            default -> List.of();
        };
        List<EffectSnapshot.ActivityEffect> activityEffects = activities.stream()
            .map(activity -> new EffectSnapshot.ActivityEffect(
                activity.getType(),
                activity.getSubject(),
                activity.getNotes(),
                normalizedPersonId(activity.getPerson(), subject),
                normalizedDealId(activity.getDeal(), attachmentDealId)))
            .sorted(Comparator.comparing(EffectSnapshot.ActivityEffect::toString))
            .toList();
        List<Note> notes = switch (subject.recordType()) {
            case "person" -> noteMapper.getNotesByPersonId(workspace.getId(), subject.recordId());
            case "deal", "document" -> noteMapper.getNotesByDealId(
                workspace.getId(), attachmentDealId);
            default -> List.of();
        };
        List<EffectSnapshot.NoteEffect> noteEffects = notes.stream()
            .map(note -> new EffectSnapshot.NoteEffect(
                note.getContent(),
                normalizedPersonId(note.getPerson(), subject),
                normalizedDealId(note.getDeal(), attachmentDealId)))
            .sorted(Comparator.comparing(EffectSnapshot.NoteEffect::toString))
            .toList();
        List<EffectSnapshot.NotificationEffect> notificationEffects = jdbcTemplate.query(
            "SELECT recipient_id, type, category, severity, title, body, actor_label,"
                + " source_type, source_id FROM notification"
                + " WHERE workspace_id = ? AND source_type = ? AND source_id = ?"
                + " ORDER BY title, body, id",
            (result, row) -> new EffectSnapshot.NotificationEffect(
                result.getInt("recipient_id"),
                result.getString("type"),
                result.getString("category"),
                result.getString("severity"),
                result.getString("title"),
                result.getString("body"),
                result.getString("actor_label"),
                result.getString("source_type"),
                normalizedId(result.getObject("source_id", Integer.class), subject.recordId())),
            workspace.getId(),
            subject.recordType(),
            subject.recordId());
        Deal deal = "deal".equals(subject.recordType())
                || "document".equals(subject.recordType())
            ? dealMapper.getDealById(workspace.getId(), attachmentDealId)
            : null;
        Person person = "person".equals(subject.recordType())
            ? personMapper.getPersonById(workspace.getId(), subject.recordId())
            : null;
        List<String> statuses = new ArrayList<>();
        statuses.addAll(jdbcTemplate.queryForList(
            "SELECT status FROM rule_execution"
                + " WHERE workspace_id = ? AND rule_id = ?"
                + " AND trigger_entity_type = ? AND trigger_entity_id = ? ORDER BY id",
            String.class,
            workspace.getId(),
            rule.getId(),
            subject.recordType(),
            subject.recordId()).stream().map(WorkflowRunReadService::normalizeLegacyStatus).toList());
        statuses.addAll(jdbcTemplate.queryForList(
            "SELECT status FROM workflow_run"
                + " WHERE workspace_id = ? AND workflow_id = ?"
                + " AND record_type = ? AND record_id = ? ORDER BY id",
            String.class,
            workspace.getId(),
            workflow.getId(),
            subject.recordType(),
            subject.recordId()));
        statuses.sort(String::compareTo);
        return new EffectSnapshot(
            tagIds,
            taskEffects,
            activityEffects,
            noteEffects,
            notificationEffects,
            deal == null ? null : deal.getOwnerId(),
            deal == null ? null : deal.getStageId(),
            person != null && person.getFirstResponseDueAt() != null,
            new EffectSnapshot.RunOutcome(statuses, statuses.size()),
            actionInvocationCount(subject.recordType(), subject.recordId()));
    }

    private static long dueDateOffset(String dueDate, LocalDate today) {
        return dueDate == null
            ? Long.MIN_VALUE : ChronoUnit.DAYS.between(today, LocalDate.parse(dueDate));
    }

    private static Integer normalizedPersonId(Person person, Subject subject) {
        return person == null ? null : normalizedId(person.getId(), subject.recordId());
    }

    private static Integer normalizedDealId(Deal deal, int attachmentDealId) {
        return deal == null ? null : normalizedId(deal.getId(), attachmentDealId);
    }

    private static Integer normalizedId(Integer actualId, int subjectId) {
        return actualId != null && actualId == subjectId ? NORMALIZED_SUBJECT_ID : actualId;
    }

    private int actionInvocationCount(String recordType, int recordId) {
        return (int) mockingDetails(actionExecutor).getInvocations().stream()
            .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
            .filter(invocation -> {
                Object argument = invocation.getArgument(1);
                return argument instanceof AutomationActionContext context
                    && recordType.equals(context.recordType())
                    && recordId == context.entityId();
            })
            .count();
    }

    private int actionInvocationCount(String recordType, List<Company> companies) {
        return companies.stream()
            .mapToInt(company -> actionInvocationCount(recordType, company.getId()))
            .sum();
    }

    private int matchedExecutionCount(int ruleId) {
        return count(
            "SELECT COUNT(*) FROM rule_execution"
                + " WHERE workspace_id = ? AND rule_id = ? AND status = 'matched'",
            workspace.getId(), ruleId);
    }

    private int workflowRunCount(int ruleId) {
        Workflow workflow = requireWorkflow(ruleId);
        return count(
            "SELECT COUNT(*) FROM workflow_run WHERE workspace_id = ? AND workflow_id = ?",
            workspace.getId(), workflow.getId());
    }

    private String canonicalFailureCode(RuleDto rule, int recordId) {
        Workflow workflow = requireWorkflow(rule.getId());
        String code = jdbcTemplate.queryForObject(
            "SELECT failure_code FROM workflow_run"
                + " WHERE workspace_id = ? AND workflow_id = ? AND record_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            String.class,
            workspace.getId(),
            workflow.getId(),
            recordId);
        assertNotNull(code);
        return code;
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private void drainSchedulerWork() {
        for (int cycle = 0; cycle < MAX_SCHEDULER_DRAIN_CYCLES; cycle++) {
            makeRetryRunsDue();
            processOneSchedulerClaim();
            SchedulerWorkState state = schedulerWorkState();
            if (state.quiescent()) {
                return;
            }
        }
        SchedulerWorkState state = schedulerWorkState();
        throw new IllegalStateException(
            "Workflow scheduler work did not quiesce within "
                + MAX_SCHEDULER_DRAIN_CYCLES
                + " cycles; pending outbox=" + state.pendingOutbox()
                + "; stuck runs=" + state.nonterminalRuns());
    }

    private void makeRetryRunsDue() {
        jdbcTemplate.update(
            "UPDATE workflow_run SET resume_at = CURRENT_TIMESTAMP(6)"
                + " WHERE workspace_id = ? AND status = 'waiting' AND wait_kind = 'retry'"
                + " AND resume_at > CURRENT_TIMESTAMP(6)",
            workspace.getId());
    }

    private SchedulerWorkState schedulerWorkState() {
        List<String> pendingOutbox = jdbcTemplate.queryForList(
            "SELECT CONCAT(id, ':', status) FROM workflow_trigger_outbox"
                + " WHERE workspace_id = ? AND status IN ('pending', 'leased') ORDER BY id",
            String.class,
            workspace.getId());
        List<String> nonterminalRuns = jdbcTemplate.queryForList(
            "SELECT CONCAT(id, ':', status, COALESCE(CONCAT('/', wait_kind), ''))"
                + " FROM workflow_run WHERE workspace_id = ?"
                + " AND (status IN ('queued', 'running')"
                + " OR (status = 'waiting' AND wait_kind = 'retry')) ORDER BY id",
            String.class,
            workspace.getId());
        return new SchedulerWorkState(pendingOutbox, nonterminalRuns);
    }

    private boolean processOneSchedulerClaim() {
        return tenantWorkScope.inWorkspace(workspace.getId(), () -> {
            WorkflowWorkClaim claim = claimTransaction.claimNext(workspace.getId());
            if (claim == null) {
                return false;
            }
            if (claim.kind() == WorkflowWorkClaim.Kind.TRIGGER) {
                outboxWorker.process(workspace.getId(), claim.id(), claim.leaseOwner());
            } else {
                runWorker.process(claim);
            }
            return true;
        });
    }

    private Company createCompany() {
        Company company = newCompany();
        createdCompanyIds.add(company.getId());
        return company;
    }

    private Company createCompany(String industry) {
        Company company = createCompany();
        company.setIndustry(industry);
        assertEquals(1, companyMapper.update(company));
        return company;
    }

    private Person createPerson(Company company) {
        Person person = newPerson(company);
        createdPersonIds.add(person.getId());
        return person;
    }

    private Pipeline createPipeline() {
        Pipeline pipeline = newPipeline();
        createdPipelineIds.add(pipeline.getId());
        return pipeline;
    }

    private Deal createDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = newDeal(pipeline, stage, company);
        createdDealIds.add(deal.getId());
        return deal;
    }

    private Tag createTag() {
        Tag tag = newTag();
        createdTagIds.add(tag.getId());
        return tag;
    }

    private User createUser() {
        User user = newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    private DealPair dealPair() {
        Pipeline pipeline = createPipeline();
        Stage stage = newStage(pipeline, 0);
        return new DealPair(
            createDeal(pipeline, stage, createCompany()),
            createDeal(pipeline, stage, createCompany()));
    }

    private DocumentSubject createDocumentSubject(Pipeline pipeline, Stage stage) {
        Deal deal = createDeal(pipeline, stage, createCompany());
        jdbcTemplate.update(
            "INSERT INTO deal_document"
                + " (workspace_id, deal_id, type, locale, status, version, title, content, currency)"
                + " VALUES (?, ?, 'quote', 'en', 'draft', 1, 'Quote', '{}', 'JPY')",
            workspace.getId(), deal.getId());
        Integer documentId = jdbcTemplate.queryForObject(
            "SELECT id FROM deal_document WHERE workspace_id = ? AND deal_id = ?",
            Integer.class,
            workspace.getId(),
            deal.getId());
        if (documentId == null) {
            throw new AssertionError("Document fixture was not inserted");
        }
        return new DocumentSubject(documentId, deal.getId());
    }

    private Workspace createForeignWorkspace() {
        Workspace foreign = new Workspace();
        foreign.setName("Parity owner workspace");
        foreign.setSlug("parity-owner-" + unique());
        foreign.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(foreign);
        createdForeignWorkspaceIds.add(foreign.getId());
        return foreign;
    }

    private Person createForeignPerson(Workspace ownerWorkspace) {
        Person person = new Person();
        String suffix = unique();
        person.setName("Shared parity " + suffix);
        person.setEmail(suffix + ".shared@example.com");
        person.setTitle("Engineer");
        person.setWorkspaceId(ownerWorkspace.getId());
        personMapper.insert(person);
        createdForeignPersonIds.add(person.getId());
        return person;
    }

    private static Subject subject(String recordType, int recordId) {
        return new Subject(recordType, recordId, null);
    }

    private static Subject subject(String recordType, int recordId, int attachmentDealId) {
        return new Subject(recordType, recordId, attachmentDealId);
    }

    private static RuleAction addTag(int tagId) {
        RuleAction action = new RuleAction();
        action.setType("add_tag");
        action.setTagId(tagId);
        return action;
    }

    private static RuleAction noteAction(String body) {
        RuleAction action = new RuleAction();
        action.setType("create_note");
        action.setBody(body);
        return action;
    }

    private static RuleAction notifyAction(String title) {
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle(title);
        action.setBody("Parity notification body");
        action.setSeverity("info");
        return action;
    }

    private static SegmentDefinition fieldCondition(String field, String op, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(field);
        condition.setOp(op);
        condition.setValue(value);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
    }

    @AfterEach
    void deleteCreatedRuntimeState() {
        for (int recipientId : createdUserIds) {
            notificationMapper.deleteAllForRecipient(workspace.getId(), recipientId);
        }
        notificationMapper.deleteAllForRecipient(workspace.getId(), currentUser.getId());
        TableLifecycle workflow = TenantLifecycleRegistry.require("workflow");
        jdbcTemplate.update(
            "UPDATE workflow SET runtime_owner = 'legacy', enabled = FALSE"
                + " WHERE workspace_id = ?",
            workspace.getId());
        for (var preparation : workflow.preparations()) {
            tenantTeardownTransaction.prepare(
                workspace.getId(), workflow, (NullifyReference) preparation);
        }
        for (String table : List.of(
                "workflow_intervention", "workflow_invocation_record", "workflow_invocation",
                "workflow_recipe_origin", "workflow_step_attempt", "workflow_step_run",
                "workflow_run", "workflow_trigger_outbox", "workflow_runtime_workspace",
                "rule_execution", "job_run", "workflow_version", "workflow", "rule")) {
            drain(TenantLifecycleRegistry.require(table));
        }
        for (int personId : createdPersonIds) {
            deleteEffects("person_id", personId);
        }
        for (int dealId : createdDealIds) {
            deleteEffects("deal_id", dealId);
        }
        for (int dealId : createdDealIds) {
            jdbcTemplate.update(
                "DELETE FROM deal WHERE workspace_id = ? AND id = ?",
                workspace.getId(), dealId);
        }
        for (int personId : createdPersonIds) {
            jdbcTemplate.update(
                "DELETE FROM person WHERE workspace_id = ? AND id = ?",
                workspace.getId(), personId);
        }
        for (int pipelineId : createdPipelineIds) {
            jdbcTemplate.update(
                "DELETE FROM stage WHERE workspace_id = ? AND pipeline_id = ?",
                workspace.getId(), pipelineId);
            jdbcTemplate.update(
                "DELETE FROM pipeline WHERE workspace_id = ? AND id = ?",
                workspace.getId(), pipelineId);
        }
        for (int companyId : createdCompanyIds) {
            jdbcTemplate.update(
                "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                workspace.getId(), companyId);
        }
        for (int tagId : createdTagIds) {
            tagMapper.delete(workspace.getId(), tagId);
        }
        for (int foreignPersonId : createdForeignPersonIds) {
            jdbcTemplate.update("DELETE FROM person_share WHERE person_id = ?", foreignPersonId);
            jdbcTemplate.update("DELETE FROM person WHERE id = ?", foreignPersonId);
        }
        for (int foreignWorkspaceId : createdForeignWorkspaceIds) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", foreignWorkspaceId);
        }
        for (int userId : createdUserIds) {
            workspaceMapper.removeMember(workspace.getId(), userId);
            userMapper.delete(userId);
        }
        workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
        userMapper.delete(currentUser.getId());
    }

    private void deleteEffects(String referenceColumn, int recordId) {
        for (String table : List.of("task", "activity", "note")) {
            jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE workspace_id = ? AND " + referenceColumn + " = ?",
                workspace.getId(), recordId);
        }
    }

    private void drain(TableLifecycle declaration) {
        while (tenantTeardownTransaction.deleteBatch(
                workspace.getId(), declaration, 100) > 0) {
        }
    }

    private record Subject(String recordType, int recordId, Integer attachmentDealId) { }

    private record PairedSnapshots(EffectSnapshot legacy, EffectSnapshot canonical) { }

    private record DealPair(Deal legacy, Deal canonical) { }

    private record DocumentSubject(int documentId, int dealId) { }

    private record SchedulerWorkState(
        List<String> pendingOutbox,
        List<String> nonterminalRuns
    ) {

        private boolean quiescent() {
            return pendingOutbox.isEmpty() && nonterminalRuns.isEmpty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedDedupeConfiguration {

        @Bean
        @Primary
        WorkflowDedupeKey parityWorkflowDedupeKey() {
            return new WorkflowDedupeKey(Clock.fixed(
                Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
        }
    }
}
