package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import ooo.klae.connex.backend.services.LegacyWorkflowBackfillTransaction;

/** Verifies legacy workflow backfill persistence and replay against real MySQL. */
class LegacyWorkflowBackfillIntegrationTest extends AbstractMapperTest {

    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowVersionMapper workflowVersionMapper;
    @Autowired private LegacyWorkflowBackfillTransaction backfillTransaction;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsOneActiveVersionAndReplaysWithoutAdditionalRows() {
        int initialLinks = workflowMapper.countLegacyRuleLinks(workspace.getId());
        User creator = newUser();
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
        rule.setRunAsUserId(creator.getId());
        rule.setCreatedById(creator.getId());
        ruleMapper.insert(rule);

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(workflow);
        assertEquals(rule.getName(), workflow.getName());
        assertEquals(rule.getDescription(), workflow.getDescription());
        assertEquals(rule.isEnabled(), workflow.isEnabled());
        assertEquals(creator.getId(), workflow.getDraftRunAsUserId());
        assertNotNull(workflow.getActiveVersionId());
        WorkflowVersion active = workflowVersionMapper.getById(
            workspace.getId(), workflow.getId(), workflow.getActiveVersionId());
        assertNotNull(active);
        assertEquals(1, active.getVersionNumber());
        assertEquals(rule.getTriggerConfig(), active.getTriggerConfig());
        assertEquals(rule.getActionsJson(), active.getActionsJson());
        assertEquals(creator.getId(), active.getRunAsUserId());
        assertEquals(creator.getId(), active.getCreatedById());
        assertNull(active.getPublishedById());
        assertArrayEquals(active.getDefinitionHash(), workflowVersionMapper.getById(
            workspace.getId(), workflow.getId(), active.getId()).getDefinitionHash());
        assertEquals(initialLinks + 1, workflowMapper.countLegacyRuleLinks(workspace.getId()));
        assertEquals(0, workflowMapper.countUnpairedLegacyRules(workspace.getId()));

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        Workflow replayed = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertEquals(workflow.getId(), replayed.getId());
        assertEquals(workflow.getActiveVersionId(), replayed.getActiveVersionId());
        assertEquals(1, workflowVersionMapper.listByWorkflow(
            workspace.getId(), workflow.getId()).size());
    }
}
