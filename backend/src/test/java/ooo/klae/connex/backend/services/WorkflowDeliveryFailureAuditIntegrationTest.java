package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/**
 * Drives a legacy rule whose first action succeeds and whose second fails through the durable trigger
 * outbox, with the real worker, action executor and audit services.
 *
 * <p>{@code create_note} audits by joining the delivery transaction, which locks the workspace's
 * integrity head on it. When {@code log_activity} then fails, its failure audit must not wait on that
 * head for the InnoDB lock-wait timeout and be lost (#1879). And because the failing action rethrows
 * through a participating transactional proxy, the delivery used to roll back entirely and be retried
 * until it dead-lettered, losing the note too (#1928). Each action now runs in its own savepoint, so
 * the delivery commits {@code "partial"}: the note stays, the failed action's own writes are undone,
 * and the workspace's audit chain stays unbroken across the savepoint.
 */
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=true",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowDeliveryFailureAuditIntegrationTest extends AbstractServiceTest {
    private static final long WELL_INSIDE_LOCK_WAIT_MS = 15_000;

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowRuntimeOwnershipService ownershipService;
    @Autowired private WorkflowRuntimeClaimTransaction claimTransaction;
    @Autowired private WorkflowTriggerOutboxWorker outboxWorker;
    @Autowired private WorkflowTriggerOutboxMapper outboxMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RuleTriggerPublisher ruleTriggerPublisher;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;
    @MockitoSpyBean private ReferenceService referenceService;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private AuditIntegrityService auditIntegrityService;

    private Integer freshWorkspaceId;
    private Integer rewrittenRuleId;
    private Long deliveredOutboxId;
    private String originalActionsJson;

    @Test
    void aFailingActionAfterAnAuditedOneFinishesPartialWithoutStallingTheDelivery() throws Exception {
        String activityTitle = "Probe activity " + unique();

        long elapsedMs = deliverRule(List.of(
                action("create_note", "Probe note", null, null),
                action("log_activity", "Probe activity body", activityTitle, "t".repeat(40))));

        assertTrue(elapsedMs < WELL_INSIDE_LOCK_WAIT_MS,
                "the delivery stalled " + elapsedMs + " ms on its own integrity head");
        assertCompletedPartially("log_activity");
        assertEquals(1, notes("Probe note"));
        assertEquals(1, audits("note.create", "success", null));
        assertEquals(0, activities(activityTitle));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log"
                        + " WHERE action = 'activity.create' AND outcome = 'failure' AND target_label = ?"
                        + " AND workspace_id = ? AND actor_id = ?",
                Integer.class, activityTitle, freshWorkspaceId, currentUser.getId()),
                "the failing action's audit must be recorded in the tenant's own chain, attributed to"
                        + " the rule's actor");
        assertWorkspaceAuditChainIsUnbroken();
    }

    /**
     * The failing action writes and audits its activity before it throws through
     * {@code ActivityService}'s transactional proxy, so only its savepoint can explain why that row
     * and its audit are gone while the earlier note and its audit stay.
     */
    @Test
    void aFailingActionsOwnWritesAreUndoneWhileEarlierActionsAreKept() throws Exception {
        String activityTitle = "Undone activity " + unique();
        doThrow(new IllegalStateException("reference sync failed"))
                .when(referenceService).syncReferences(anyInt(), eq(ReferenceService.SOURCE_ACTIVITY),
                        anyInt(), any());

        deliverRule(List.of(
                action("create_note", "Kept note", null, null),
                action("log_activity", "Undone activity body", activityTitle, "call")));

        assertCompletedPartially("log_activity");
        assertEquals(1, notes("Kept note"));
        assertEquals(1, audits("note.create", "success", null));
        assertEquals(0, activities(activityTitle));
        assertEquals(0, audits("activity.create", "success", activityTitle));
        assertWorkspaceAuditChainIsUnbroken();
    }

    /**
     * The first action's audit append is undone with its savepoint, so the next action's append reuses
     * the freed chain index. The chain must stay contiguous, each row must keep exactly one checkpoint,
     * and every row must still verify.
     */
    @Test
    void anAuditAfterAnUndoneOneReusesTheFreedChainIndexWithoutBreakingIntegrity() throws Exception {
        doThrow(new IllegalStateException("reference sync failed"))
                .when(referenceService).syncReferences(anyInt(), eq(ReferenceService.SOURCE_ACTIVITY),
                        anyInt(), any());

        deliverRule(List.of(
                action("log_activity", "Undone first body", "Undone first " + unique(), "call"),
                action("create_note", "Note after an undone audit", null, null)));

        assertCompletedPartially("log_activity");
        assertEquals(1, notes("Note after an undone audit"));
        assertWorkspaceAuditChainIsUnbroken();
        assertEquals(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log"
                        + " WHERE chain_scope_type = 'workspace' AND chain_scope_id = ?",
                        Integer.class, freshWorkspaceId),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log_integrity_checkpoint"
                        + " WHERE scope_type = 'workspace' AND scope_id = ?",
                        Integer.class, freshWorkspaceId));
        List<AuditLog> recorded = auditLogMapper.findRecent(freshWorkspaceId, 500, 0);
        assertFalse(recorded.isEmpty());
        for (AuditLog entry : recorded) {
            assertTrue(auditIntegrityService.hasValidIntegrity(entry), entry.getAction());
        }
    }

    /**
     * A transient failure inside an action is not the action's fault, and a deadlock takes the
     * delivery's transaction with it, so the delivery must not commit "partial": it rolls back whole,
     * keeping nothing from the earlier action, and the worker schedules a retry.
     */
    @Test
    void aTransientActionFailureRollsTheWholeDeliveryBackForRetry() throws Exception {
        doThrow(new CannotAcquireLockException("simulated lock wait timeout"))
                .when(referenceService).syncReferences(anyInt(), eq(ReferenceService.SOURCE_ACTIVITY),
                        anyInt(), any());

        deliverRule(List.of(
                action("create_note", "Retried note", null, null),
                action("log_activity", "Retried activity body", "Retried activity " + unique(), "call")));

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT status, last_error_code FROM workflow_trigger_outbox WHERE workspace_id = ? AND id = ?",
                freshWorkspaceId, deliveredOutboxId);
        assertEquals("pending", outbox.get("status"), "the delivery must be released for retry: " + outbox);
        assertEquals("trigger_delivery_failed", outbox.get("last_error_code"));
        assertEquals(0, notes("Retried note"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_execution WHERE workspace_id = ? AND rule_id = ?",
                Integer.class, freshWorkspaceId, rewrittenRuleId));
        assertWorkspaceAuditChainIsUnbroken();
    }

    private long deliverRule(List<RuleAction> actions) throws Exception {
        committedWorkspace();
        Person person = newPerson(null);
        WorkflowDto workflow = createEnabledWorkflow("Failure audit " + unique());
        ownershipService.rollBackToLegacy(workflow.id(), workflow.activeVersionId());
        Workflow legacy = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNotNull(legacy.getLegacyRuleId());
        originalActionsJson = jdbcTemplate.queryForObject(
                "SELECT actions_json FROM rule WHERE workspace_id = ? AND id = ?",
                String.class, workspace.getId(), legacy.getLegacyRuleId());
        rewrittenRuleId = legacy.getLegacyRuleId();
        assertEquals(1, jdbcTemplate.update(
                "UPDATE rule SET actions_json = ? WHERE workspace_id = ? AND id = ?",
                objectMapper.writeValueAsString(actions), workspace.getId(), legacy.getLegacyRuleId()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ruleTriggerPublisher.publish(workspace.getId(), "person", person.getId(), "person.updated"));
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id());
        WorkflowWorkClaim claim = claimTransaction.claimNext(workspace.getId());
        assertNotNull(claim);
        assertEquals(outbox.getId(), claim.id());
        deliveredOutboxId = outbox.getId();

        long started = System.nanoTime();
        outboxWorker.process(workspace.getId(), outbox.getId(), claim.leaseOwner());
        return (System.nanoTime() - started) / 1_000_000;
    }

    private void assertCompletedPartially(String failedActionType) {
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT status, last_error_code FROM workflow_trigger_outbox WHERE workspace_id = ? AND id = ?",
                freshWorkspaceId, deliveredOutboxId);
        assertEquals("completed", outbox.get("status"), "the delivery must commit, not retry: " + outbox);
        assertNull(outbox.get("last_error_code"));
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT status, detail FROM rule_execution WHERE workspace_id = ? AND rule_id = ?"
                        + " ORDER BY id DESC LIMIT 1",
                freshWorkspaceId, rewrittenRuleId);
        assertEquals("partial", run.get("status"));
        assertTrue(String.valueOf(run.get("detail")).contains(failedActionType), String.valueOf(run.get("detail")));
    }

    private int notes(String content) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM note WHERE workspace_id = ? AND content = ?",
                Integer.class, freshWorkspaceId, content);
    }

    private int activities(String subject) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity WHERE workspace_id = ? AND subject = ?",
                Integer.class, freshWorkspaceId, subject);
    }

    private int audits(String action, String outcome, String targetLabel) {
        return targetLabel == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND outcome = ? AND workspace_id = ?",
                        Integer.class, action, outcome, freshWorkspaceId)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_log WHERE action = ? AND outcome = ? AND workspace_id = ?"
                                + " AND target_label = ?",
                        Integer.class, action, outcome, freshWorkspaceId, targetLabel);
    }

    /**
     * Every row in the workspace's integrity chain links to the one before it, and the head names the
     * last row: an append undone with a savepoint must leave no gap and no dangling head.
     */
    private void assertWorkspaceAuditChainIsUnbroken() {
        List<Map<String, Object>> chain = jdbcTemplate.queryForList(
                "SELECT chain_index, prev_hash, row_hash FROM audit_log"
                        + " WHERE chain_scope_type = 'workspace' AND chain_scope_id = ? ORDER BY chain_index",
                freshWorkspaceId);
        assertFalse(chain.isEmpty());
        long expectedIndex = ((Number) chain.getFirst().get("chain_index")).longValue();
        String previousHash = null;
        for (Map<String, Object> row : chain) {
            assertEquals(expectedIndex++, ((Number) row.get("chain_index")).longValue());
            if (previousHash != null) {
                assertEquals(previousHash, row.get("prev_hash"));
            }
            previousHash = (String) row.get("row_hash");
        }
        Map<String, Object> head = jdbcTemplate.queryForMap(
                "SELECT next_chain_index, current_hash FROM audit_log_integrity_head"
                        + " WHERE scope_type = 'workspace' AND scope_id = ?",
                freshWorkspaceId);
        assertEquals(expectedIndex, ((Number) head.get("next_chain_index")).longValue());
        assertEquals(previousHash, head.get("current_hash"));
    }

    /**
     * Removes the workflow and legacy rule this test committed. The test rewrites the rule's actions
     * so they no longer match the workflow's projection, which the startup legacy backfill rightly
     * refuses; left behind, that pair would fail every later context load in the same schema. The
     * original actions are restored first, so even a drain that fails partway leaves a consistent
     * pair. Only this test's own workspace is drained, never the shared default one. Mirrors
     * {@code WorkflowTriggerExactlyOnceIntegrationTest}'s cleanup.
     */
    @AfterEach
    void restoreAndDrainWorkflowState() {
        if (rewrittenRuleId != null && freshWorkspaceId != null) {
            jdbcTemplate.update("UPDATE rule SET actions_json = ? WHERE workspace_id = ? AND id = ?",
                    originalActionsJson, freshWorkspaceId, rewrittenRuleId);
        }
        if (freshWorkspaceId == null) {
            return;
        }
        TableLifecycle workflow = TenantLifecycleRegistry.require("workflow");
        jdbcTemplate.update(
                "UPDATE workflow SET runtime_owner = 'legacy', enabled = FALSE WHERE workspace_id = ?",
                freshWorkspaceId);
        for (var preparation : workflow.preparations()) {
            tenantTeardownTransaction.prepare(freshWorkspaceId, workflow, (NullifyReference) preparation);
        }
        for (String table : List.of(
                "workflow_intervention", "workflow_invocation_record", "workflow_invocation",
                "workflow_recipe_origin", "workflow_step_attempt", "workflow_step_run",
                "workflow_run", "workflow_trigger_outbox", "workflow_runtime_workspace",
                "rule_execution", "job_run", "workflow_version", "workflow", "rule")) {
            TableLifecycle declaration = TenantLifecycleRegistry.require(table);
            while (tenantTeardownTransaction.deleteBatch(freshWorkspaceId, declaration, 100) > 0) {
            }
        }
    }

    /**
     * Deletes the committed user {@code AbstractServiceTest} made an owner of the shared default
     * workspace, which later classes would otherwise count as an eligible approver or delegate. It is
     * a separate method so a failing drain cannot skip it: JUnit runs every after-each method even when
     * one throws. Memberships cascade, audit rows keep their signed {@code integrity_actor_id}, and no
     * foreign key to {@code app_user} restricts the delete.
     */
    @AfterEach
    void deleteCommittedUser() {
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    private WorkflowDto createEnabledWorkflow(String title) {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName(title);
        request.setRecordType("person");
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(definition(title)));
        request.setCanvas(objectMapper.valueToTree(canvas()));
        WorkflowDto created = workflowService.create(request);
        WorkflowPublishRequest publication = new WorkflowPublishRequest();
        publication.setExpectedRevision(0);
        WorkflowDto published = workflowService.publish(created.id(), publication);
        assertNotNull(published.activeVersionId());
        return workflowService.enable(created.id());
    }

    private WorkflowTriggerOutbox latestOutbox(int workflowId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_trigger_outbox WHERE workspace_id = ? AND workflow_id = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class, workspace.getId(), workflowId);
        assertNotNull(id);
        WorkflowTriggerOutbox outbox = outboxMapper.getById(workspace.getId(), id);
        assertNotNull(outbox);
        assertEquals("person.updated", outbox.getTriggerEvent());
        return outbox;
    }

    private void committedWorkspace() {
        Organization organization = new Organization();
        organization.setName("Delivery audit " + unique());
        organization.setSlug("delivery-audit-" + unique());
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), currentUser.getId());
        Workspace fresh = new Workspace();
        fresh.setOrgId(organization.getId());
        fresh.setName("Delivery audit " + unique());
        fresh.setSlug("delivery-audit-" + unique());
        workspaceMapper.insert(fresh);
        workspaceMapper.addMember(fresh.getId(), currentUser.getId(), "owner");
        freshWorkspaceId = fresh.getId();
        workspace = fresh;
        authenticateAs(currentUser, fresh.getId());
    }

    private static RuleAction action(String type, String body, String title, String activityType) {
        RuleAction action = new RuleAction();
        action.setType(type);
        action.setBody(body);
        action.setTitle(title);
        action.setActivityType(activityType);
        return action;
    }

    private static WorkflowDefinition definition(String title) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.updated"));
        RuleAction notify = new RuleAction();
        notify.setType("notify");
        notify.setTitle(title);
        return new WorkflowDefinition(
                1,
                "trigger",
                List.of(
                        new WorkflowNode.Trigger("trigger", trigger),
                        new WorkflowNode.Action("action", notify),
                        new WorkflowNode.End("end")),
                List.of(
                        new WorkflowEdge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                        new WorkflowEdge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowCanvas canvas() {
        return new WorkflowCanvas(
                Map.of(
                        "trigger", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                        "action", new WorkflowCanvas.Position(BigDecimal.valueOf(300), BigDecimal.ZERO),
                        "end", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
                new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }
}
