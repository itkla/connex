package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.services.RuleEngineService;
import ooo.klae.connex.backend.services.LegacyWorkflowBackfillTransaction;
import ooo.klae.connex.backend.services.WorkflowOffboardingService;

/** Verifies legacy workflow backfill persistence and replay against real MySQL. */
class LegacyWorkflowBackfillIntegrationTest extends AbstractMapperTest {

    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowVersionMapper workflowVersionMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private LegacyWorkflowBackfillTransaction backfillTransaction;
    @Autowired private WorkflowOffboardingService workflowOffboardingService;
    @Autowired private RuleEngineService ruleEngineService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsOneActiveVersionAndReplaysWithoutAdditionalRows() throws Exception {
        int initialLinks = workflowMapper.countLegacyRuleLinks(workspace.getId());
        User creator = newUser();
        User runAs = newUser();
        assertNotEquals(creator.getId(), runAs.getId());
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");

        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Backfill integration " + unique());
        rule.setDescription("Legacy description");
        rule.setEnabled(true);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setConditionJson(null);
        rule.setActionsJson(objectMapper.writeValueAsString(List.of(action)));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(runAs.getId());
        rule.setCreatedById(creator.getId());
        ruleMapper.insert(rule);

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(workflow);
        assertEquals(rule.getName(), workflow.getName());
        assertEquals(rule.getDescription(), workflow.getDescription());
        assertEquals(rule.isEnabled(), workflow.isEnabled());
        assertEquals(runAs.getId(), workflow.getDraftRunAsUserId());
        assertNotNull(workflow.getActiveVersionId());
        WorkflowVersion active = workflowVersionMapper.getById(
            workspace.getId(), workflow.getId(), workflow.getActiveVersionId());
        assertNotNull(active);
        assertEquals(1, active.getVersionNumber());
        assertEquals(rule.getTriggerConfig(), active.getTriggerConfig());
        assertEquals(rule.getActionsJson(), active.getActionsJson());
        assertEquals(runAs.getId(), active.getRunAsUserId());
        assertEquals(creator.getId(), active.getCreatedById());
        assertNull(active.getPublishedById());
        String expectedDefinition = "{\"edges\":[{\"id\":\"action-1--next--end\","
            + "\"outcome\":\"next\",\"sourceNodeId\":\"action-1\",\"targetNodeId\":\"end\"},"
            + "{\"id\":\"trigger--next--action-1\",\"outcome\":\"next\","
            + "\"sourceNodeId\":\"trigger\",\"targetNodeId\":\"action-1\"}],"
            + "\"entryNodeId\":\"trigger\",\"nodes\":[{\"config\":{\"activityType\":null,"
            + "\"body\":null,\"dueInDays\":null,\"severity\":null,\"tagId\":null,"
            + "\"targetStageId\":null,\"targetUserId\":null,\"title\":\"Notify owner\","
            + "\"type\":\"notify\"},\"id\":\"action-1\",\"type\":\"ACTION\"},"
            + "{\"id\":\"end\",\"type\":\"END\"},{\"config\":{\"cadence\":null,"
            + "\"events\":[\"deal.won\"],\"targetStageId\":null,\"throttleMinutes\":null,"
            + "\"type\":\"entity_change\"},\"id\":\"trigger\",\"type\":\"TRIGGER\"}],"
            + "\"schemaVersion\":1}";
        String expectedCanvas = "{\"positions\":{\"action-1\":{\"x\":240,\"y\":0},"
            + "\"end\":{\"x\":480,\"y\":0},\"trigger\":{\"x\":0,\"y\":0}},"
            + "\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(
            ("{\"definition\":" + expectedDefinition + ",\"canvas\":" + expectedCanvas + "}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedDefinition, workflow.getDraftDefinitionJson());
        assertEquals(expectedCanvas, workflow.getDraftCanvasJson());
        assertEquals(expectedDefinition, active.getDefinitionJson());
        assertEquals(expectedCanvas, active.getCanvasJson());
        assertArrayEquals(
            expectedDefinition.getBytes(StandardCharsets.UTF_8),
            active.getDefinitionJson().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(
            expectedCanvas.getBytes(StandardCharsets.UTF_8),
            active.getCanvasJson().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expectedHash, active.getDefinitionHash());
        WorkflowVersion reread = workflowVersionMapper.getById(
            workspace.getId(), workflow.getId(), active.getId());
        assertEquals(expectedDefinition, reread.getDefinitionJson());
        assertEquals(expectedCanvas, reread.getCanvasJson());
        assertArrayEquals(expectedHash, reread.getDefinitionHash());
        assertEquals(initialLinks + 1, workflowMapper.countLegacyRuleLinks(workspace.getId()));
        assertEquals(0, workflowMapper.countUnpairedLegacyRules(workspace.getId()));

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow replayed = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertEquals(workflow.getId(), replayed.getId());
        assertEquals(workflow.getActiveVersionId(), replayed.getActiveVersionId());
        assertEquals(1, workflowVersionMapper.listByWorkflow(
            workspace.getId(), workflow.getId()).size());
    }

    @Test
    void creatorDeletionPreservesImmutableVersionAndAllowsBackfillReplay() throws Exception {
        User creator = newUser();
        User runAs = newUser();
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Notify owner");

        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Offboarded backfill " + unique());
        rule.setEnabled(true);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setActionsJson(objectMapper.writeValueAsString(List.of(action)));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(runAs.getId());
        rule.setCreatedById(creator.getId());
        ruleMapper.insert(rule);

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow before = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(before);
        assertNotNull(before.getActiveVersionId());
        long versionId = before.getActiveVersionId();
        WorkflowVersion immutableVersion = workflowVersionMapper.getById(
            workspace.getId(), before.getId(), versionId);
        assertEquals(creator.getId(), immutableVersion.getCreatedById());

        var plan = workflowOffboardingService.discover(creator.getId());
        workflowOffboardingService.lockWorkspaceRoots(plan);
        workflowOffboardingService.offboard(creator.getId(), plan);
        userMapper.delete(creator.getId());

        Rule redactedRule = ruleMapper.getById(workspace.getId(), rule.getId());
        Workflow redactedWorkflow = workflowMapper.getById(workspace.getId(), before.getId());
        assertFalse(redactedRule.isEnabled());
        assertNull(redactedRule.getCreatedById());
        assertEquals(runAs.getId(), redactedRule.getRunAsUserId());
        assertFalse(redactedWorkflow.isEnabled());
        assertNull(redactedWorkflow.getCreatedById());
        assertEquals(runAs.getId(), redactedWorkflow.getDraftRunAsUserId());
        assertEquals(creator.getId(), workflowVersionMapper.getById(
            workspace.getId(), before.getId(), versionId).getCreatedById());

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow replayed = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertEquals(before.getId(), replayed.getId());
        assertEquals(versionId, replayed.getActiveVersionId());
        assertEquals(1, workflowVersionMapper.listByWorkflow(
            workspace.getId(), before.getId()).size());
    }

    @Test
    void backfillAndReplayPreserveOneExecutionPerEvent() throws Exception {
        User principal = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), principal.getId(), "admin");
        var person = newPerson(newCompany());
        String taskTitle = "Backfill task " + unique();
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.updated"));
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle(taskTitle);

        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Backfill execution " + unique());
        rule.setEnabled(true);
        rule.setRecordType("person");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setActionsJson(objectMapper.writeValueAsString(List.of(action)));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(principal.getId());
        rule.setCreatedById(principal.getId());
        ruleMapper.insert(rule);

        ruleEngineService.onEntityChange(
            workspace.getId(), "person", person.getId(), "person.updated");

        assertExecutions(rule.getId(), 1);
        assertEquals(1, matchingTasks(person.getId(), taskTitle));

        backfillTransaction.backfillWorkspace(null, workspace.getId());
        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(workflow);
        assertEquals(rule.getId(), workflow.getLegacyRuleId());
        assertEquals(1, workflowVersionMapper.listByWorkflow(
            workspace.getId(), workflow.getId()).size());

        ruleEngineService.onEntityChange(
            workspace.getId(), "person", person.getId(), "person.updated");

        assertExecutions(rule.getId(), 2);
        assertEquals(2, matchingTasks(person.getId(), taskTitle));
        assertEquals(1, workflowMapper.listByWorkspace(workspace.getId()).stream()
            .filter(candidate -> candidate.getLegacyRuleId() != null)
            .filter(candidate -> candidate.getLegacyRuleId() == rule.getId())
            .count());
        assertEquals(1, workflowVersionMapper.listByWorkflow(
            workspace.getId(), workflow.getId()).size());
    }

    private void assertExecutions(int ruleId, int expected) {
        var executions = ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50);
        assertEquals(expected, executions.size());
        assertTrue(executions.stream().allMatch(
            execution -> "matched".equals(execution.getStatus())));
    }

    private long matchingTasks(int personId, String title) {
        return taskMapper.getTasksByPersonId(workspace.getId(), personId).stream()
            .filter(task -> title.equals(task.getDescription()))
            .count();
    }
}
