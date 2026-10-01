package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/**
 * Drives #1879's chain end to end: a legacy rule whose first action audits a success and whose
 * second fails and audits the failure, delivered through the durable trigger outbox with the real
 * action executor and the real audit services.
 *
 * <p>{@code create_note} audits by joining the delivery transaction, which locks the workspace's
 * integrity head on it. {@code log_activity} then fails inside {@code ActivityService.create}'s
 * {@code try} — an activity type longer than the 32-character column — and records the failure. That
 * failure append used to wait on the delivery transaction's own head for the InnoDB lock-wait
 * timeout and was then lost. The failing action also rethrows through a participating transactional
 * proxy, so the whole delivery is rolled back; the failure audit must survive that too.
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
    @Autowired private WorkflowTriggerOutboxDeliveryService outboxDeliveryService;
    @Autowired private WorkflowTriggerOutboxMapper outboxMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RuleTriggerPublisher ruleTriggerPublisher;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;

    @Test
    void aFailingActionAfterAnAuditedOneIsAuditedWithoutStallingTheDelivery() throws Exception {
        committedWorkspace();
        Person person = newPerson(null);
        String activityTitle = "Probe activity " + unique();
        WorkflowDto workflow = createEnabledWorkflow("Failure audit " + unique());
        ownershipService.rollBackToLegacy(workflow.id(), workflow.activeVersionId());
        Workflow legacy = workflowMapper.getById(workspace.getId(), workflow.id());
        assertNotNull(legacy.getLegacyRuleId());
        assertEquals(1, jdbcTemplate.update(
                "UPDATE rule SET actions_json = ? WHERE workspace_id = ? AND id = ?",
                objectMapper.writeValueAsString(List.of(
                        action("create_note", "Probe note", null, null),
                        action("log_activity", "Probe activity body", activityTitle,
                                "t".repeat(40)))),
                workspace.getId(), legacy.getLegacyRuleId()));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ruleTriggerPublisher.publish(workspace.getId(), "person", person.getId(), "person.updated"));
        WorkflowTriggerOutbox outbox = latestOutbox(workflow.id());
        WorkflowWorkClaim claim = claimTransaction.claimNext(workspace.getId());
        assertNotNull(claim);
        assertEquals(outbox.getId(), claim.id());

        long started = System.nanoTime();
        RuntimeException outcome = null;
        try {
            outboxDeliveryService.deliver(workspace.getId(), outbox.getId(), claim.leaseOwner());
        } catch (RuntimeException rolledBack) {
            outcome = rolledBack;
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < WELL_INSIDE_LOCK_WAIT_MS,
                "the delivery stalled " + elapsedMs + " ms on its own integrity head");
        assertTrue(outcome instanceof UnexpectedRollbackException,
                "the failing action rethrows through a participating transactional proxy, so the"
                        + " delivery must roll back rather than commit \"partial\"; was " + outcome);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log"
                        + " WHERE action = 'activity.create' AND outcome = 'failure' AND target_label = ?"
                        + " AND workspace_id = ? AND actor_id = ?",
                Integer.class, activityTitle, workspace.getId(), currentUser.getId()),
                "the failing action's audit must be recorded in the tenant's own chain, attributed to"
                        + " the rule's actor, even though the delivery rolled back");
    }

    /**
     * Removes the workflow and legacy rule this test committed. The test rewrites the rule's actions
     * so they no longer match the workflow's projection, which the startup legacy backfill rightly
     * refuses; left behind, that pair would fail every later context load in the same schema. It
     * also deletes the committed user {@code AbstractServiceTest} made an owner of the shared default
     * workspace, which later classes would otherwise count as an eligible approver or delegate. The
     * delivery rolls back, so no note or activity that would restrict that delete is ever committed.
     * Mirrors {@code WorkflowTriggerExactlyOnceIntegrationTest}'s cleanup.
     */
    @AfterEach
    void deleteCreatedRuntimeState() {
        TableLifecycle workflow = TenantLifecycleRegistry.require("workflow");
        jdbcTemplate.update(
                "UPDATE workflow SET runtime_owner = 'legacy', enabled = FALSE WHERE workspace_id = ?",
                workspace.getId());
        for (var preparation : workflow.preparations()) {
            tenantTeardownTransaction.prepare(workspace.getId(), workflow, (NullifyReference) preparation);
        }
        for (String table : List.of(
                "workflow_intervention", "workflow_invocation_record", "workflow_invocation",
                "workflow_recipe_origin", "workflow_step_attempt", "workflow_step_run",
                "workflow_run", "workflow_trigger_outbox", "workflow_runtime_workspace",
                "rule_execution", "job_run", "workflow_version", "workflow", "rule")) {
            TableLifecycle declaration = TenantLifecycleRegistry.require(table);
            while (tenantTeardownTransaction.deleteBatch(workspace.getId(), declaration, 100) > 0) {
            }
        }
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
