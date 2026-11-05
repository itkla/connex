package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

/** Keeps infrequent failures visible until a newer completed outcome supersedes them. */
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=false",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(isolation = Isolation.READ_COMMITTED)
class WorkflowOperationsHealthIntegrationTest extends AbstractServiceTest {
    @Autowired private RuleService ruleService;
    @Autowired private WorkflowOperationsService operationsService;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({"legacy,failed", "legacy,partial", "canonical,failed"})
    void weeklyFailureThreeDaysAgoStaysUnhealthyUntilANewerCompletedOutcome(
            String source, String status) {
        Workflow workflow = weeklyWorkflow();
        int recordId = newPerson(newCompany()).getId();
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        assertNotNull(now);
        var before = operationsService.summary();
        saveOutcome(workflow, recordId, source, status, now.minusDays(3));

        assertEquals(before.healthyCount() - 1, operationsService.summary().healthyCount());
        assertEquals(before.interventionRequiredCount() + 1,
            operationsService.summary().interventionRequiredCount());
        assertEquals(before.recentFailureCount(), operationsService.summary().recentFailureCount());
        assertEquals("degraded", operationsService.workflow(workflow.getId()).health().state());
        assertTrue(operationsService.workflow(workflow.getId()).health().signals().contains("latest_run_failed"));
        assertEquals(0, operationsService.workflow(workflow.getId()).backlog().recentFailureCount());

        saveOutcome(workflow, recordId, source, "running", now);
        assertEquals("degraded", operationsService.workflow(workflow.getId()).health().state());
        assertEquals(before.healthyCount() - 1, operationsService.summary().healthyCount());

        String recoverySource = "legacy".equals(source) ? "canonical" : "legacy";
        String recoveryStatus = "legacy".equals(recoverySource) ? "matched" : "succeeded";
        saveOutcome(workflow, recordId, recoverySource, recoveryStatus, now.minusDays(2));
        assertEquals("healthy", operationsService.workflow(workflow.getId()).health().state());
        assertEquals(before.healthyCount(), operationsService.summary().healthyCount());
        assertEquals(before.interventionRequiredCount(), operationsService.summary().interventionRequiredCount());

        saveOutcome(workflow, recordId, source, "failed", now.minusMinutes(10));
        assertEquals("degraded", operationsService.workflow(workflow.getId()).health().state());
        saveOutcome(workflow, recordId, recoverySource, recoveryStatus, now.minusMinutes(5));
        assertEquals("healthy", operationsService.workflow(workflow.getId()).health().state());
        assertEquals(before.healthyCount(), operationsService.summary().healthyCount());
        assertEquals(before.interventionRequiredCount(), operationsService.summary().interventionRequiredCount());
        assertEquals(before.recentFailureCount() + 1, operationsService.summary().recentFailureCount());
        assertEquals(1, operationsService.workflow(workflow.getId()).backlog().recentFailureCount());
    }

    private Workflow weeklyWorkflow() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("weekly");
        SegmentCondition name = new SegmentCondition();
        name.setType("field");
        name.setField("name");
        name.setOp("contains");
        name.setValue("Review");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(name));
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Weekly review");
        RuleRequest request = new RuleRequest();
        request.setName("Weekly review " + unique());
        request.setEnabled(true);
        request.setRecordType("person");
        request.setExecutionMode("user");
        request.setTrigger(trigger);
        request.setCondition(condition);
        request.setActions(List.of(action));
        var rule = ruleService.create(request);
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(workflow);
        assertTrue(workflow.isEnabled());
        assertNotNull(workflow.getLegacyRuleId());
        assertNotNull(workflow.getActiveVersionId());
        return workflow;
    }

    private void saveOutcome(
            Workflow workflow, int recordId, String source, String status, LocalDateTime at) {
        if ("legacy".equals(source)) {
            RuleExecution execution = new RuleExecution();
            execution.setWorkspaceId(workspace.getId());
            execution.setRuleId(workflow.getLegacyRuleId());
            execution.setTriggerEntityType("person");
            execution.setTriggerEntityId(recordId);
            execution.setStatus(status);
            execution.setDedupeKey("health-" + unique());
            execution.setDetail("{}");
            ruleMapper.insertExecution(execution);
            assertEquals(1, jdbc.update(
                "UPDATE rule_execution SET executed_at = ? WHERE workspace_id = ? AND id = ?",
                at, workspace.getId(), execution.getId()));
            return;
        }
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(workspace.getId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersionId(workflow.getActiveVersionId());
        run.setStatus("queued");
        run.setTriggerType("schedule");
        run.setTriggerEvent("schedule.weekly");
        run.setTriggerKey("health-" + unique());
        run.setRecordType("person");
        run.setRecordId(recordId);
        run.setDedupeKey("health-" + unique());
        run.setExecutionMode("user");
        run.setActorUserId(currentUser.getId());
        run.setAttributionUserId(currentUser.getId());
        run.setCurrentNodeId("action-1");
        run.setStartedAt(at);
        runMapper.insertRun(run);
        if ("running".equals(status)) {
            assertEquals(1, jdbc.update("""
                UPDATE workflow_run
                SET status = 'running', lease_owner = ?,
                    lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE)
                WHERE workspace_id = ? AND id = ?
                """, UUID.randomUUID().toString(), workspace.getId(), run.getId()));
        } else {
            boolean failed = "failed".equals(status);
            assertEquals(1, jdbc.update("""
                UPDATE workflow_run
                SET status = ?, current_node_id = ?, finished_at = ?,
                    failure_node_id = ?, failure_code = ?, failure_message = ?
                WHERE workspace_id = ? AND id = ?
                """,
                status, failed ? "action-1" : "end", at.plusSeconds(1),
                failed ? "action-1" : null, failed ? "configuration_invalid" : null,
                failed ? "The action configuration is unavailable." : null,
                workspace.getId(), run.getId()));
        }
    }
}
