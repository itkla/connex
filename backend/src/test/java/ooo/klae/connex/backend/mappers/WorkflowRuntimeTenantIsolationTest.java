package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.beans.Workspace;

/** Durable workflow runtime and legacy rules-projection isolation across tenant dimensions. */
class WorkflowRuntimeTenantIsolationTest extends AbstractMapperTest {

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowVersionMapper workflowVersionMapper;
    @Autowired private WorkflowRunMapper workflowRunMapper;

    @Test
    void runAndStepReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        User actor = newUser();
        Workflow workflow = saveWorkflow(actor);
        WorkflowVersion version = saveVersion(workflow, actor);
        WorkflowRun run = saveRun(workflow, version, actor);
        WorkflowStepRun step = saveStep(run);

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(workflowRunMapper.getById(
                    unauthorized.getId(), workflow.getId(), run.getId()));
            assertNull(workflowRunMapper.getViewById(
                    unauthorized.getId(), workflow.getId(), run.getId()));
            assertEquals(List.of(), workflowRunMapper.getSteps(
                    unauthorized.getId(), run.getId()));
            assertEquals(0, workflowRunMapper.cancelImmediately(
                    unauthorized.getId(), run.getId(), LocalDateTime.now()));
            assertEquals(0, workflowRunMapper.cancelExistingStep(
                    unauthorized.getId(), run.getId(), step.getNodeId(), LocalDateTime.now()));
        }

        WorkflowRun unchangedRun = workflowRunMapper.getById(
                workspace.getId(), workflow.getId(), run.getId());
        assertNotNull(unchangedRun);
        assertEquals("queued", unchangedRun.getStatus());
        assertEquals("queued", workflowRunMapper.getSteps(
                workspace.getId(), run.getId()).getFirst().getStatus());
    }

    @Test
    void rulesProjectionReadsAndMutationsAreRefusedInSiblingAndForeignOrganizationWorkspaces() {
        User actor = newUser();
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Rule matrix " + unique());
        rule.setEnabled(true);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig("{}");
        rule.setActionsJson("[]");
        rule.setExecutionMode("user");
        rule.setRunAsUserId(actor.getId());
        rule.setCreatedById(actor.getId());
        ruleMapper.insert(rule);
        RuleExecution execution = new RuleExecution();
        execution.setWorkspaceId(workspace.getId());
        execution.setRuleId(rule.getId());
        execution.setStatus("matched");
        execution.setDedupeKey("rule-matrix-" + unique());
        execution.setDetail("{}");
        ruleMapper.insertExecution(execution);

        for (Workspace unauthorized : unauthorizedWorkspaces()) {
            assertNull(ruleMapper.getById(unauthorized.getId(), rule.getId()));
            assertNull(ruleMapper.getExecutionById(
                    unauthorized.getId(), rule.getId(), execution.getId()));
            assertEquals(0, ruleMapper.updateEnabled(
                    unauthorized.getId(), rule.getId(), false));
        }

        Rule unchangedRule = ruleMapper.getById(workspace.getId(), rule.getId());
        assertNotNull(unchangedRule);
        assertTrue(unchangedRule.isEnabled());
        assertNotNull(ruleMapper.getExecutionById(
                workspace.getId(), rule.getId(), execution.getId()));
    }

    private Workflow saveWorkflow(User actor) {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspace.getId());
        workflow.setName("Workflow matrix " + unique());
        workflow.setEnabled(false);
        workflow.setDraftRevision(1);
        workflow.setDraftRecordType("deal");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftRunAsUserId(actor.getId());
        workflow.setDraftDefinitionJson("{\"schemaVersion\":1}");
        workflow.setDraftCanvasJson("{}");
        workflow.setCreatedById(actor.getId());
        workflow.setUpdatedById(actor.getId());
        workflowMapper.insert(workflow);
        return workflow;
    }

    private WorkflowVersion saveVersion(Workflow workflow, User actor) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(workspace.getId());
        version.setWorkflowId(workflow.getId());
        version.setVersionNumber(1);
        version.setName(workflow.getName());
        version.setRecordType("deal");
        version.setTriggerType("manual");
        version.setTriggerConfig("{}");
        version.setActionsJson("[]");
        version.setExecutionMode("user");
        version.setRunAsUserId(actor.getId());
        version.setCreatedById(actor.getId());
        version.setPublishedById(actor.getId());
        version.setDefinitionJson("{\"schemaVersion\":1}");
        version.setCanvasJson("{}");
        version.setDefinitionHash(new byte[32]);
        workflowVersionMapper.insert(version);
        return version;
    }

    private WorkflowRun saveRun(Workflow workflow, WorkflowVersion version, User actor) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(workspace.getId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersionId(version.getId());
        run.setStatus("queued");
        run.setTriggerType("manual");
        run.setTriggerEvent("manual");
        run.setTriggerKey("manual-" + unique());
        run.setRecordType("deal");
        run.setRecordId(1);
        run.setDedupeKey("run-matrix-" + unique());
        run.setExecutionMode("user");
        run.setActorUserId(actor.getId());
        run.setAttributionUserId(actor.getId());
        run.setCurrentNodeId("end");
        run.setStartedAt(LocalDateTime.now());
        workflowRunMapper.insertRun(run);
        return run;
    }

    private WorkflowStepRun saveStep(WorkflowRun run) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(workspace.getId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(0);
        step.setNodeId("end");
        step.setNodeType("end");
        step.setStatus("queued");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setStartedAt(LocalDateTime.now());
        workflowRunMapper.insertStep(step);
        return step;
    }

    private List<Workspace> unauthorizedWorkspaces() {
        return List.of(
                newWorkspaceInOrg(workspace.getOrgId()),
                newWorkspaceInOrg(newOrganization().getId()));
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        Workspace created = new Workspace();
        created.setName("Workflow runtime matrix " + unique());
        created.setSlug("workflow-runtime-matrix-" + unique());
        created.setOrgId(orgId);
        workspaceMapper.insert(created);
        return created;
    }

    private Organization newOrganization() {
        Organization organization = new Organization();
        organization.setName("Workflow runtime matrix " + unique());
        organization.setSlug("workflow-runtime-matrix-org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }
}
