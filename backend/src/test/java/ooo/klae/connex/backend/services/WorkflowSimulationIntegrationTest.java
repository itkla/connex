package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowSimulateRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;

class WorkflowSimulationIntegrationTest extends AbstractServiceTest {

    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowSimulationService simulationService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private RuleActionExecutor actionExecutor;

    @Test
    void simulationWritesNoLedgerAuditOutboxOrActionRows() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        WorkflowDto workflow = workflowService.create(createRequest());
        Map<String, Long> before = mutationCounts();

        WorkflowSimulationDto result = simulationService.simulate(
            workflow.id(), new WorkflowSimulateRequest(workflow.draftRevision(), deal.getId()));

        assertEquals(WorkflowSimulationDto.Result.WOULD_COMPLETE, result.result());
        assertEquals(before, mutationCounts());
        verifyNoInteractions(actionExecutor);
    }

    private WorkflowCreateRequest createRequest() {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("Read-only simulation");
        request.setRecordType("deal");
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(definition()));
        request.setCanvas(objectMapper.valueToTree(canvas()));
        return request;
    }

    private static WorkflowDefinition definition() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.updated"));
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle("Simulation must not create this task");
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Action("create-task", action),
                new WorkflowNode.End("end")),
            List.of(
                new WorkflowEdge(
                    "trigger-action", "trigger", "create-task", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge(
                    "action-end", "create-task", "end", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowCanvas canvas() {
        return new WorkflowCanvas(
            Map.of(
                "trigger", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.ZERO),
                "create-task", new WorkflowCanvas.Position(
                    BigDecimal.valueOf(300), BigDecimal.ZERO),
                "end", new WorkflowCanvas.Position(BigDecimal.valueOf(600), BigDecimal.ZERO)),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }

    private Map<String, Long> mutationCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("workflow", count("workflow"));
        counts.put("workflow_version", count("workflow_version"));
        counts.put("workflow_run", count("workflow_run"));
        counts.put("workflow_step_run", count("workflow_step_run"));
        counts.put("rule", count("rule"));
        counts.put("rule_execution", count("rule_execution"));
        counts.put("task", count("task"));
        counts.put("activity", count("activity"));
        counts.put("note", count("note"));
        counts.put("notification", count("notification"));
        counts.put("person_tag", countJunction("person_tag", "person", "person_id"));
        counts.put("company_tag", countJunction("company_tag", "company", "company_id"));
        counts.put("deal_tag", countJunction("deal_tag", "deal", "deal_id"));
        counts.put("audit_log", count("audit_log"));
        if (tableExists("workflow_trigger_outbox")) {
            counts.put("workflow_trigger_outbox", count("workflow_trigger_outbox"));
        }
        return Map.copyOf(counts);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Long.class,
            workspace.getId());
    }

    private long countJunction(String junction, String owner, String foreignKey) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + junction + " j JOIN " + owner
                + " o ON o.id = j." + foreignKey + " WHERE o.workspace_id = ?",
            Long.class,
            workspace.getId());
    }

    private boolean tableExists(String table) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = ?",
            Long.class,
            table);
        return count != null && count > 0;
    }
}
