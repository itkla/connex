package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowRecipeInstallRequest;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;

/** Proves recipe publication executes effects with the shipped gate closed, or refuses safely. */
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=false",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(isolation = Isolation.READ_COMMITTED)
class WorkflowRecipeExecutionIntegrationTest extends AbstractServiceTest {

    @Autowired private WorkflowRecipeService recipeService;
    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowRuntimeService runtimeService;
    @Autowired private WorkflowRuntimeProperties runtimeProperties;
    @Autowired private WorkflowOperationsService operationsService;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"person-job-change-follow-up", "deal-won-handoff"})
    void installedRecipeCreatesRunAndExpectedRecordEffects(String recipeKey) {
        String taskTitle = "Recipe task " + unique();
        Map<String, JsonNode> parameters = parameters(taskTitle);
        WorkflowDto installed = install(recipeKey, parameters);
        assertFalse(installed.enabled());
        assertFalse(runtimeProperties.enabled());
        assertEquals("legacy", installed.runtimeOwner());
        assertTrue(workflowService.enable(installed.id()).enabled());
        int recordId;
        String recordType;
        String event;
        if ("person-job-change-follow-up".equals(recipeKey)) {
            recordId = newPerson(newCompany()).getId();
            recordType = "person";
            event = "person.job_changed";
        } else {
            var pipeline = newPipeline();
            recordId = newDeal(pipeline, newStage(pipeline, 0), newCompany()).getId();
            recordType = "deal";
            event = "deal.won";
        }
        var dispatch = new WorkflowTriggerDispatch.EntityChange(
            workspace.getId(), recordType, recordId, event, "recipe-" + unique(), Instant.now());

        runtimeService.dispatch(dispatch);
        runtimeService.dispatch(dispatch);

        Integer ruleId = workflowMapper.getById(workspace.getId(), installed.id()).getLegacyRuleId();
        assertNotNull(ruleId);
        var executions = ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 10);
        assertEquals(1, executions.size());
        assertEquals("matched", executions.getFirst().getStatus());
        var tasks = "person".equals(recordType)
            ? taskMapper.getTasksByPersonId(workspace.getId(), recordId)
            : taskMapper.getTasksByDealId(workspace.getId(), recordId);
        assertEquals(1, tasks.stream().filter(task -> taskTitle.equals(task.getDescription())).count());
        var automatedTask = tasks.stream().filter(task -> taskTitle.equals(task.getDescription()))
            .findFirst().orElseThrow();
        assertNotNull(automatedTask.getAssignedTo());
        assertEquals(currentUser.getId(), automatedTask.getAssignedTo().getId());
        if ("deal".equals(recordType)) {
            assertEquals(1, activityMapper.getActivitiesByDealId(workspace.getId(), recordId).stream()
                .filter(activity -> "Recipe handoff".equals(activity.getNotes())).count());
        }
        assertEquals("succeeded", operationsService.runs(null, null, null, 25, null)
            .items().stream().filter(run -> run.workflowId() == installed.id())
            .findFirst().orElseThrow().run().status());
    }

    @Test
    void conditionWithSeparateEndsValidatesPublishesAndOnlyRunsMatchingBranch() {
        var preview = recipeService.preview("person-job-change-follow-up",
            new WorkflowRecipePreviewRequest(null, null, parameters("Conditional task"), null));
        var field = new SegmentCondition();
        field.setType("field");
        field.setField("name");
        field.setOp("contains");
        field.setValue("Match");
        var condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(field));
        List<WorkflowNode> nodes = new ArrayList<>(preview.definition().nodes());
        nodes.add(new WorkflowNode.Condition("condition", condition));
        nodes.add(new WorkflowNode.End("no-end"));
        List<WorkflowEdge> edges = new ArrayList<>();
        for (var edge : preview.definition().edges()) {
            edges.add("trigger".equals(edge.sourceNodeId())
                ? new WorkflowEdge(edge.id(), "trigger", "condition", WorkflowEdge.Outcome.NEXT)
                : edge);
            if ("trigger".equals(edge.sourceNodeId())) {
                edges.add(new WorkflowEdge("yes", "condition", edge.targetNodeId(), WorkflowEdge.Outcome.YES));
            }
        }
        edges.add(new WorkflowEdge("no", "condition", "no-end", WorkflowEdge.Outcome.NO));
        var positions = new LinkedHashMap<>(preview.canvas().positions());
        positions.put("condition", new WorkflowCanvas.Position(BigDecimal.ZERO, BigDecimal.TEN));
        positions.put("no-end", new WorkflowCanvas.Position(BigDecimal.TEN, BigDecimal.TEN));
        var request = new WorkflowCreateRequest();
        request.setName("Conditional workflow");
        request.setRecordType("person");
        request.setExecutionMode("user");
        request.setDefinition(objectMapper.valueToTree(new WorkflowDefinition(1, "trigger", nodes, edges)));
        request.setCanvas(objectMapper.valueToTree(new WorkflowCanvas(positions, preview.canvas().viewport())));
        var created = workflowService.create(request);
        assertTrue(workflowService.validate(created.id()).canPublish());
        var publish = new WorkflowPublishRequest();
        publish.setExpectedRevision(0);
        workflowService.publish(created.id(), publish);
        workflowService.enable(created.id());
        for (String name : List.of("Match contact", "Other contact")) {
            var person = newPerson(newCompany());
            person.setName(name);
            personMapper.update(person);
            runtimeService.dispatch(new WorkflowTriggerDispatch.EntityChange(workspace.getId(),
                "person", person.getId(), "person.job_changed", unique(), Instant.now()));
            assertEquals(name.startsWith("Match") ? 1 : 0,
                taskMapper.getTasksByPersonId(workspace.getId(), person.getId()).size());
        }
    }

    @Test
    void coolingCompanyRecipeExplainsUnsupportedTaskAndCreatesNothing() {
        Map<String, JsonNode> parameters = parameters("Cooling review");
        int before = workflowService.list(false).size();

        var preview = recipeService.preview("cooling-company-review",
            new WorkflowRecipePreviewRequest(null, null, parameters, null));

        assertFalse(preview.validation().valid());
        assertFalse(preview.validation().canPublish());
        assertEquals(WorkflowDiagnosticCode.ACTION_RECORD_TYPE_UNSUPPORTED,
            preview.validation().errors().getFirst().code());
        assertThrows(ConflictException.class, () -> recipeService.install("cooling-company-review",
            new WorkflowRecipeInstallRequest(preview.previewHash(), null, null, parameters)));
        assertEquals(before, workflowService.list(false).size());
    }

    @Test
    void operationsIncludesActiveEngineFailuresAndFiltersWithoutCallingThemHealthy() {
        WorkflowDto installed = install("person-job-change-follow-up", parameters("Operations task"));
        workflowService.enable(installed.id());
        Integer ruleId = workflowMapper.getById(workspace.getId(), installed.id()).getLegacyRuleId();
        assertNotNull(ruleId);
        int healthyBefore = operationsService.summary().healthyCount();
        for (String status : List.of("running", "partial", "failed", "matched")) {
            RuleExecution execution = new RuleExecution();
            execution.setWorkspaceId(workspace.getId());
            execution.setRuleId(ruleId);
            execution.setStatus(status);
            execution.setDedupeKey("operations-" + unique());
            execution.setDetail("{}");
            ruleMapper.insertExecution(execution);
        }

        var summary = operationsService.summary();
        assertEquals(healthyBefore - 1, summary.healthyCount());
        assertTrue(summary.interventionRequiredCount() >= 1);
        assertTrue(summary.recentFailureCount() >= 2);
        var detail = operationsService.workflow(installed.id());
        assertEquals("degraded", detail.health().state());
        assertEquals(2, detail.backlog().recentFailureCount());
        for (String status : List.of("running", "partial", "failed", "succeeded")) {
            var page = operationsService.runs(status, null, null, 25, null);
            assertTrue(page.items().stream().anyMatch(run -> run.workflowId() == installed.id()
                && status.equals(run.run().status())));
        }
        assertEquals(2, operationsService.runs(null, "execution", null, 25, null).items().stream()
            .filter(run -> run.workflowId() == installed.id()).count());
        assertTrue(operationsService.runs(null, "reference", null, 25, null).items().stream()
            .noneMatch(run -> run.workflowId() == installed.id()));
        assertTrue(operationsService.runs(null, null, currentUser.getId(), 25, null).items().stream()
            .noneMatch(run -> run.workflowId() == installed.id()));
    }

    private WorkflowDto install(String key, Map<String, JsonNode> parameters) {
        var preview = recipeService.preview(key,
            new WorkflowRecipePreviewRequest(null, null, parameters, null));
        assertTrue(preview.validation().canPublish());
        return recipeService.install(key,
            new WorkflowRecipeInstallRequest(preview.previewHash(), null, null, parameters)).workflow();
    }

    private Map<String, JsonNode> parameters(String taskTitle) {
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        parameters.put("actorUserId", objectMapper.valueToTree(currentUser.getId()));
        parameters.put("targetUserId", objectMapper.valueToTree(currentUser.getId()));
        parameters.put("taskTitle", objectMapper.valueToTree(taskTitle));
        parameters.put("activityNote", objectMapper.valueToTree("Recipe handoff"));
        parameters.put("dueInDays", objectMapper.valueToTree(7));
        parameters.put("coolingDays", objectMapper.valueToTree(30));
        return parameters;
    }
}
