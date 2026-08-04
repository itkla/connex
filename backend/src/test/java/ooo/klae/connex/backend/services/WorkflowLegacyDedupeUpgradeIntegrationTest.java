package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;

@Import(WorkflowLegacyDedupeUpgradeIntegrationTest.FixedDedupeConfiguration.class)
class WorkflowLegacyDedupeUpgradeIntegrationTest extends AbstractServiceTest {

    @Autowired private RuleService ruleService;
    @Autowired private RuleEngineService ruleEngineService;
    @Autowired private WorkflowRuntimeService workflowRuntimeService;
    @Autowired private WorkflowRuntimeOwnershipService ownershipService;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private WorkflowRuntimeProperties runtimeProperties;
    @MockitoBean private AuditService auditService;
    @MockitoSpyBean private RuleActionExecutor actionExecutor;

    @BeforeEach
    void enableCanonicalRuntime() {
        when(runtimeProperties.enabled()).thenReturn(true);
    }

    @Test
    void legacyOwnedScheduleDoesNotRepeatAPreUpgradeBucketEffect() {
        Company company = newCompany();
        RuleDto rule = scheduleRule(newTag().getId());
        seedLegacyExecution(rule.getId(), company.getId(), company.getId() + ":20260803");
        clearInvocations(actionExecutor);

        ruleEngineService.runSchedule(
            new WorkflowTriggerDispatch.ScheduleTick(
                workspace.getId(), "daily", "20260803"));

        assertSingleHistoricalEffect(rule.getId());
        verifyNoInteractions(actionExecutor);
    }

    @Test
    void canonicalOwnedScheduleDoesNotRepeatAPreUpgradeBucketEffect() {
        Company company = newCompany();
        RuleDto rule = scheduleRule(newTag().getId());
        cutOver(rule.getId());
        seedLegacyExecution(rule.getId(), company.getId(), company.getId() + ":20260803");
        clearInvocations(actionExecutor);

        workflowRuntimeService.dispatch(
            new WorkflowTriggerDispatch.ScheduleTick(
                workspace.getId(), "daily", "20260803"));

        assertSingleHistoricalEffect(rule.getId());
        verifyNoInteractions(actionExecutor);
    }

    @Test
    void legacyOwnedThrottleDoesNotRepeatAPreUpgradeWindowEffect() {
        Company company = newCompany();
        RuleDto rule = throttledRule(newTag().getId());
        Instant occurredAt = Instant.parse("2026-08-03T12:34:00Z");
        long window = occurredAt.getEpochSecond() / 3600;
        seedLegacyExecution(
            rule.getId(),
            company.getId(),
            company.getId() + ":company.updated:t60:" + window);
        clearInvocations(actionExecutor);

        ruleEngineService.onEntityChange(entityDispatch(company.getId(), occurredAt));

        assertSingleHistoricalEffect(rule.getId());
        verifyNoInteractions(actionExecutor);
    }

    @Test
    void canonicalOwnedThrottleDoesNotRepeatAPreUpgradeWindowEffect() {
        Company company = newCompany();
        RuleDto rule = throttledRule(newTag().getId());
        cutOver(rule.getId());
        Instant occurredAt = Instant.parse("2026-08-03T12:34:00Z");
        long window = occurredAt.getEpochSecond() / 3600;
        seedLegacyExecution(
            rule.getId(),
            company.getId(),
            company.getId() + ":company.updated:t60:" + window);
        clearInvocations(actionExecutor);

        workflowRuntimeService.dispatch(entityDispatch(company.getId(), occurredAt));

        assertSingleHistoricalEffect(rule.getId());
        verifyNoInteractions(actionExecutor);
    }

    private RuleDto scheduleRule(int tagId) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        RuleRequest request = request(tagId, trigger);
        request.setCondition(noActivity());
        return ruleService.create(request);
    }

    private RuleDto throttledRule(int tagId) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        trigger.setThrottleMinutes(60);
        return ruleService.create(request(tagId, trigger));
    }

    private RuleRequest request(int tagId, RuleTrigger trigger) {
        RuleAction action = new RuleAction();
        action.setType("add_tag");
        action.setTagId(tagId);
        RuleRequest request = new RuleRequest();
        request.setName("Upgrade dedupe " + unique());
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setActions(List.of(action));
        request.setExecutionMode("user");
        return request;
    }

    private static SegmentDefinition noActivity() {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("predicate");
        condition.setKey("no_activity");
        condition.setDays(30);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
    }

    private WorkflowTriggerDispatch.EntityChange entityDispatch(
            int companyId, Instant occurredAt) {
        return new WorkflowTriggerDispatch.EntityChange(
            workspace.getId(),
            "company",
            companyId,
            "company.updated",
            "upgrade-event",
            occurredAt);
    }

    private void cutOver(int ruleId) {
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        ownershipService.cutOverToCanonical(
            workflow.getId(), workflow.getActiveVersionId());
    }

    private void seedLegacyExecution(int ruleId, int recordId, String dedupeKey) {
        RuleExecution execution = new RuleExecution();
        execution.setWorkspaceId(workspace.getId());
        execution.setRuleId(ruleId);
        execution.setTriggerEntityType("company");
        execution.setTriggerEntityId(recordId);
        execution.setStatus("matched");
        execution.setDedupeKey(dedupeKey);
        ruleMapper.insertExecution(execution);
    }

    private void assertSingleHistoricalEffect(int ruleId) {
        assertEquals(
            1,
            ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50).size());
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE workspace_id = ? AND workflow_id = ?",
                Integer.class,
                workspace.getId(),
                workflow.getId()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedDedupeConfiguration {

        @Bean
        @Primary
        WorkflowDedupeKey transitionWorkflowDedupeKey() {
            return new WorkflowDedupeKey(Clock.fixed(
                Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
        }
    }
}
