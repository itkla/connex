package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.services.WorkflowRuntimeClaimService.CanonicalClaim;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=true",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowTriggerExactlyOnceIntegrationTest extends AbstractServiceTest {

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowRuntimeService workflowRuntimeService;
    @Autowired private WorkflowRuntimeOwnershipService ownershipService;
    @Autowired private WorkflowRuntimeClaimService claimService;
    @Autowired private WorkflowRuntimeClaimTransaction claimTransaction;
    @Autowired private WorkflowTriggerOutboxDeliveryService outboxDeliveryService;
    @Autowired private WorkflowTriggerOutboxWorker outboxWorker;
    @Autowired private WorkflowRunWorker runWorker;
    @Autowired private WorkflowTriggerOutboxMapper outboxMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private RuleTriggerPublisher ruleTriggerPublisher;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoSpyBean private WorkflowPrincipalLockService principalLockService;
    @MockitoSpyBean private RuleActionExecutor actionExecutor;

    private final List<Integer> createdCompanyIds = new ArrayList<>();
    private final List<Integer> createdDealIds = new ArrayList<>();
    private final List<Integer> createdPipelineIds = new ArrayList<>();

    @Test
    void companyUpdatedDuplicateDeliveryReplayAndSchedulerRestartExecuteOnce() throws Exception {
        String title = "Restart ownership " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title);
        Company company = createCompany();
        clearInvocations(actionExecutor);

        publishCompanyUpdated(company.getId());
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id());
        WorkflowWorkClaim abandoned = claimTransaction.claimNext(workspace.getId());
        assertNotNull(abandoned);
        assertEquals(WorkflowWorkClaim.Kind.TRIGGER, abandoned.kind());
        assertEquals(outbox.getId(), abandoned.id());
        assertEquals(1, jdbcTemplate.update(
            "UPDATE workflow_trigger_outbox"
                + " SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)"
                + " WHERE workspace_id = ? AND id = ? AND lease_owner = ?",
            workspace.getId(), outbox.getId(), abandoned.leaseOwner()));

        runConcurrentSchedulerRestart();

        assertEquals(
            WorkflowTriggerOutboxDeliveryService.DeliveryResult.STALE,
            outboxDeliveryService.deliver(
                workspace.getId(), outbox.getId(), abandoned.leaseOwner()));
        WorkflowDispatchResult replay = workflowRuntimeService.dispatch(entityDispatch(outbox));
        drainSchedulerWork();

        assertEquals(1, replay.candidates());
        assertEquals(0, replay.started());
        assertEquals(1, replay.replayed());
        assertSingleCanonicalEffect(workflow.id(), title);
        assertEquals("completed", outboxStatus(outbox.getId()));
        assertEquals(2, outboxDeliveryAttempts(outbox.getId()));
    }

    @Test
    void rollbackAfterCanonicalExecutionFailsClosedAndReplayRemainsCanonical() {
        String title = "Rollback ownership " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title);
        Company company = createCompany();
        clearInvocations(actionExecutor);

        publishCompanyUpdated(company.getId());
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id());
        drainSchedulerWork();
        assertSingleCanonicalEffect(workflow.id(), title);
        assertEquals(1, jdbcTemplate.update(
            "UPDATE workflow_run"
                + " SET started_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY),"
                + " finished_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)"
                + " WHERE workspace_id = ? AND workflow_id = ?",
            workspace.getId(), workflow.id()));

        assertThrows(
            ConflictException.class,
            () -> ownershipService.rollBackToLegacy(
                workflow.id(), workflow.activeVersionId()));
        Workflow persisted = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNull(persisted.getLegacyRuleId());
        assertEquals("canonical", persisted.getRuntimeOwner());

        workflowRuntimeService.dispatch(entityDispatch(outbox));
        drainSchedulerWork();

        assertSingleCanonicalEffect(workflow.id(), title);
    }

    @Test
    void committedCanonicalClaimBetweenRollbackDiscoveryAndLockIsVisibleToFence() {
        String title = "Interleaved rollback " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title);
        Company company = createCompany();
        WorkflowTriggerDispatch.EntityChange dispatch = entityDispatch(
            company.getId(), "interleaved-" + unique());
        AtomicBoolean claimCommitted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (claimCommitted.compareAndSet(false, true)) {
                CanonicalClaim claim = claimService.claimEntity(workflow.id(), dispatch);
                assertTrue(claim.started());
            }
            return invocation.callRealMethod();
        }).when(principalLockService).lockUserMutation(
            eq(workspace.getId()), eq(currentUser.getId()), any(), any());

        assertThrows(
            ConflictException.class,
            () -> ownershipService.rollBackToLegacy(
                workflow.id(), workflow.activeVersionId()));

        assertTrue(claimCommitted.get());
        Workflow persisted = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNull(persisted.getLegacyRuleId());
        assertEquals("canonical", persisted.getRuntimeOwner());
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM workflow_run"
                + " WHERE workspace_id = ? AND workflow_id = ?",
                workspace.getId(), workflow.id()));
    }

    @Test
    void databaseRejectsOldBinaryFirstLegacyAttachmentAfterCanonicalHistory()
            throws Exception {
        String title = "Old binary rollback " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title);
        Company company = createCompany();
        CanonicalClaim claim = claimService.claimEntity(
            workflow.id(), entityDispatch(company.getId(), "old-binary-" + unique()));
        Rule legacyRule = insertUnpairedLegacyRule(title);
        assertTrue(claim.started());

        DataAccessException failure = assertThrows(
            DataAccessException.class,
            () -> workflowMapper.attachLegacyRuleAndCompareAndSwapRuntimeOwner(
                workspace.getId(),
                workflow.id(),
                workflow.activeVersionId(),
                legacyRule.getId(),
                "canonical",
                "legacy",
                currentUser.getId()));

        assertEquals("45000", sqlState(failure));
        Workflow persisted = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNull(persisted.getLegacyRuleId());
        assertEquals("canonical", persisted.getRuntimeOwner());
    }

    @Test
    void documentApprovedCanonicalRunReachesActionOnce() {
        String title = "Document ownership " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title, "document", "document.approved");
        int documentId = createDocument();
        clearInvocations(actionExecutor);

        publishEntityChange("document", documentId, "document.approved");
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id(), "document.approved");
        drainSchedulerWork();
        workflowRuntimeService.dispatch(entityDispatch(outbox));
        drainSchedulerWork();

        assertEquals("document", outbox.getRecordType());
        assertEquals(documentId, outbox.getRecordId());
        assertSingleCanonicalEffect(workflow.id(), title);
    }

    @Test
    void companyUpdatedLegacyOwnerDuplicateDeliveryAndReplayExecuteOnce() {
        String title = "Legacy ownership " + unique();
        WorkflowDto workflow = createEnabledWorkflow(title);
        Company company = createCompany();
        ownershipService.rollBackToLegacy(workflow.id(), workflow.activeVersionId());
        Workflow rolledBack = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNotNull(rolledBack.getLegacyRuleId());
        assertEquals("legacy", rolledBack.getRuntimeOwner());
        clearInvocations(actionExecutor);

        publishCompanyUpdated(company.getId());
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id());
        drainSchedulerWork();
        workflowRuntimeService.dispatch(entityDispatch(outbox));
        drainSchedulerWork();

        assertSingleLegacyEffect(
            workflow.id(), rolledBack.getLegacyRuleId(), title);
    }

    private WorkflowDto createEnabledWorkflow(String title) {
        return createEnabledWorkflow(title, "company", "company.updated");
    }

    private WorkflowDto createEnabledWorkflow(String title, String recordType, String event) {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName(title);
        request.setRecordType(recordType);
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(definition(title, event)));
        request.setCanvas(objectMapper.valueToTree(canvas()));
        WorkflowDto created = workflowService.create(request);
        WorkflowPublishRequest publication = new WorkflowPublishRequest();
        publication.setExpectedRevision(0);
        WorkflowDto published = workflowService.publish(created.id(), publication);
        WorkflowDto enabled = workflowService.enable(created.id());
        Workflow persisted = workflowMapper.getById(workspace.getId(), created.id());
        assertEquals("canonical", enabled.runtimeOwner());
        assertNull(persisted.getLegacyRuleId());
        assertNotNull(published.activeVersionId());
        return enabled;
    }

    private Company createCompany() {
        Company company = newCompany();
        createdCompanyIds.add(company.getId());
        return company;
    }

    /**
     * A committed draft document on a committed deal. The row is written directly because this test
     * runs without a surrounding transaction and only needs a valid subject for the record guard.
     */
    private int createDocument() {
        Pipeline pipeline = newPipeline();
        createdPipelineIds.add(pipeline.getId());
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), createCompany());
        createdDealIds.add(deal.getId());
        jdbcTemplate.update(
            "INSERT INTO deal_document"
                + " (workspace_id, deal_id, type, locale, status, version, title, content, currency)"
                + " VALUES (?, ?, 'quote', 'en', 'draft', 1, 'Quote', '{}', 'JPY')",
            workspace.getId(), deal.getId());
        Integer documentId = jdbcTemplate.queryForObject(
            "SELECT id FROM deal_document WHERE workspace_id = ? AND deal_id = ?",
            Integer.class, workspace.getId(), deal.getId());
        assertNotNull(documentId);
        return documentId;
    }

    private void publishCompanyUpdated(int companyId) {
        publishEntityChange("company", companyId, "company.updated");
    }

    private void publishEntityChange(String recordType, int recordId, String event) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> ruleTriggerPublisher.publish(
            workspace.getId(), recordType, recordId, event));
    }

    private WorkflowTriggerOutbox latestOutbox(int workflowId) {
        return latestOutbox(workflowId, "company.updated");
    }

    private WorkflowTriggerOutbox latestOutbox(int workflowId, String event) {
        Long id = jdbcTemplate.queryForObject(
            "SELECT id FROM workflow_trigger_outbox"
                + " WHERE workspace_id = ? AND workflow_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            Long.class,
            workspace.getId(),
            workflowId);
        assertNotNull(id);
        WorkflowTriggerOutbox outbox = outboxMapper.getById(workspace.getId(), id);
        assertNotNull(outbox);
        assertEquals(event, outbox.getTriggerEvent());
        return outbox;
    }

    private WorkflowTriggerDispatch.EntityChange entityDispatch(
            WorkflowTriggerOutbox outbox) {
        return new WorkflowTriggerDispatch.EntityChange(
            outbox.getWorkspaceId(),
            outbox.getRecordType(),
            outbox.getRecordId(),
            outbox.getTriggerEvent(),
            outbox.getTriggerKey(),
            outbox.getOccurredAt().toInstant(ZoneOffset.UTC));
    }

    private WorkflowTriggerDispatch.EntityChange entityDispatch(int companyId, String key) {
        return new WorkflowTriggerDispatch.EntityChange(
            workspace.getId(),
            "company",
            companyId,
            "company.updated",
            key,
            Instant.parse("2026-08-08T12:00:00Z"));
    }

    private Rule insertUnpairedLegacyRule(String title) throws Exception {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle(title);
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName(title);
        rule.setEnabled(false);
        rule.setRecordType("company");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setActionsJson(objectMapper.writeValueAsString(List.of(action)));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(currentUser.getId());
        rule.setCreatedById(currentUser.getId());
        ruleMapper.insert(rule);
        assertTrue(rule.getId() > 0);
        return rule;
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private void runConcurrentSchedulerRestart() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> processAfterRelease(ready, start));
            Future<Boolean> second = executor.submit(() -> processAfterRelease(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            boolean firstProcessed = first.get(30, TimeUnit.SECONDS);
            boolean secondProcessed = second.get(30, TimeUnit.SECONDS);
            assertTrue(firstProcessed || secondProcessed);
            drainSchedulerWork();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean processAfterRelease(
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return processOneSchedulerClaim();
    }

    private void drainSchedulerWork() {
        for (int processed = 0; processed < 8; processed++) {
            if (!processOneSchedulerClaim()) {
                return;
            }
        }
        throw new IllegalStateException("Workflow scheduler work did not drain within its bound");
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

    private void assertSingleCanonicalEffect(int workflowId, String title) {
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM workflow_run"
                + " WHERE workspace_id = ? AND workflow_id = ?",
                workspace.getId(), workflowId));
        assertEquals(
            "succeeded",
            jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_run"
                    + " WHERE workspace_id = ? AND workflow_id = ?",
                String.class,
                workspace.getId(), workflowId),
            () -> jdbcTemplate.queryForObject(
                "SELECT CONCAT(failure_code, ': ', failure_message) FROM workflow_run"
                    + " WHERE workspace_id = ? AND workflow_id = ?",
                String.class,
                workspace.getId(), workflowId));
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM notification"
                + " WHERE workspace_id = ? AND recipient_id = ? AND title = ?",
                workspace.getId(), currentUser.getId(), title));
        verify(actionExecutor, times(1)).execute(
            argThat(action -> title.equals(action.getTitle())),
            any(AutomationActionContext.class));
    }

    private void assertSingleLegacyEffect(
            int workflowId, int ruleId, String title) {
        assertEquals(
            0,
            count("SELECT COUNT(*) FROM workflow_run"
                + " WHERE workspace_id = ? AND workflow_id = ?",
                workspace.getId(), workflowId));
        assertEquals(
            1,
            ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50).size());
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM notification"
                + " WHERE workspace_id = ? AND recipient_id = ? AND title = ?",
                workspace.getId(), currentUser.getId(), title));
        verify(actionExecutor, times(1)).execute(
            argThat(action -> title.equals(action.getTitle())),
            any(AutomationActionContext.class));
    }

    private String outboxStatus(long outboxId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM workflow_trigger_outbox"
                + " WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(), outboxId);
    }

    private int outboxDeliveryAttempts(long outboxId) {
        return count(
            "SELECT delivery_attempt_count FROM workflow_trigger_outbox"
                + " WHERE workspace_id = ? AND id = ?",
            workspace.getId(), outboxId);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static WorkflowDefinition definition(String title, String event) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(event));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle(title);
        return new WorkflowDefinition(
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
    }

    private static WorkflowCanvas canvas() {
        return new WorkflowCanvas(
            Map.of(
                "trigger", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "action", new WorkflowCanvas.Position(BigDecimal.valueOf(300), BigDecimal.ZERO),
                "end", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }

    @AfterEach
    void deleteCreatedRuntimeState() {
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
        for (int dealId : createdDealIds) {
            jdbcTemplate.update(
                "DELETE FROM deal WHERE workspace_id = ? AND id = ?",
                workspace.getId(), dealId);
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
        workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
        userMapper.delete(currentUser.getId());
    }

    private void drain(TableLifecycle declaration) {
        while (tenantTeardownTransaction.deleteBatch(
                workspace.getId(), declaration, 100) > 0) {
        }
    }
}
