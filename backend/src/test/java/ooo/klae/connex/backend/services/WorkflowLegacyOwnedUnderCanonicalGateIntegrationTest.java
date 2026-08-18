package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@Import(WorkflowLegacyOwnedUnderCanonicalGateIntegrationTest.FixedDedupeConfiguration.class)
@TestPropertySource(properties = {
    "connex.workflows.runtime.enabled=true",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WorkflowLegacyOwnedUnderCanonicalGateIntegrationTest extends AbstractServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-03T12:34:00Z");

    @Autowired private RuleService ruleService;
    @Autowired private WorkflowRuntimeService workflowRuntimeService;
    @Autowired private WorkflowTriggerIntake workflowTriggerIntake;
    @Autowired private RuleTriggerPublisher ruleTriggerPublisher;
    @Autowired private RuleTriggerListener ruleTriggerListener;
    @Autowired private SegmentService segmentService;
    @Autowired private LegacyWorkflowBackfillTransaction backfillTransaction;
    @Autowired private WorkflowRuntimeClaimTransaction claimTransaction;
    @Autowired private WorkflowTriggerOutboxWorker outboxWorker;
    @Autowired private WorkflowRunWorker runWorker;
    @Autowired private WorkflowTriggerOutboxMapper outboxMapper;
    @Autowired private WorkflowMapper workflowMapper;
    @Autowired private RuleMapper ruleMapper;
    @Autowired private TenantTeardownTenantTransaction tenantTeardownTransaction;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AuditService auditService;
    @MockitoSpyBean private RuleActionExecutor actionExecutor;

    private final List<Integer> createdCompanyIds = new ArrayList<>();
    private final List<Integer> createdTagIds = new ArrayList<>();

    @Test
    void entityChangeExecutesOnceOnlyAfterDurableOutboxDelivery() {
        Company company = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityTagRule(tag.getId());
        clearInvocations(actionExecutor);

        RuleTriggerEvent event = new RuleTriggerEvent(
            workspace.getId(),
            "company",
            company.getId(),
            "company.updated",
            "listener-inert-" + unique(),
            OCCURRED_AT);
        synchronousListener().onTrigger(event);
        verifyNoInteractions(actionExecutor);
        publishCompanyUpdated(company.getId());
        assertEquals(0, matchedExecutionCount(rule.getId()));

        drainSchedulerWork();

        assertSingleLegacyEffect(rule, company.getId(), tag.getId());
    }

    @Test
    void duplicateDeliverySandwichAndFullRedispatchRemainExactlyOnce() {
        Company company = createCompany();
        Tag tag = createTag();
        RuleDto rule = entityTagRule(tag.getId());
        clearInvocations(actionExecutor);

        publishCompanyUpdated(company.getId());
        WorkflowTriggerOutbox outbox = latestOutbox(rule.getId());
        drainSchedulerWork();
        workflowRuntimeService.dispatch(entityDispatch(outbox));
        workflowRuntimeService.dispatch(entityDispatch(outbox));
        drainSchedulerWork();

        assertSingleLegacyEffect(rule, company.getId(), tag.getId());
        assertEquals("completed", outboxStatus(outbox.getId()));
    }

    @Test
    void scheduleEnumeratesTheLegacyRecordSetAcrossHundredRowPages() {
        String industry = "Paged-" + unique();
        List<Company> companies = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            companies.add(createCompany(industry));
        }
        Tag tag = createTag();
        SegmentDefinition condition = fieldCondition("industry", "equals", industry);
        RuleDto rule = scheduleTagRule(tag.getId(), condition);
        List<Integer> expected = segmentService.evaluate(
            workspace.getId(), currentUser.getId(), "company", condition);
        clearInvocations(actionExecutor);

        workflowTriggerIntake.enqueue(new WorkflowTriggerDispatch.ScheduleTick(
            workspace.getId(), "daily", "legacy-page-20260803"));
        drainSchedulerWork();

        List<Integer> actual = companyMapper.getCompaniesByTagId(
                workspace.getId(), tag.getId()).stream()
            .map(Company::getId)
            .filter(expected::contains)
            .sorted()
            .toList();
        assertEquals(105, expected.size());
        assertEquals(expected.stream().sorted().toList(), actual);
        assertEquals(105, matchedExecutionCount(rule.getId()));
        assertEquals(105, actionInvocationCount("company", companies));
        assertEquals(0, workflowRunCount(rule.getId()));
    }

    /** Startup backfill makes this direct-insert state unreachable in a ready production process. */
    @Test
    void unpairedRuleExecutesNothingUntilStartupBackfillCreatesItsWorkflow() throws Exception {
        Company company = createCompany();
        Tag tag = createTag();
        Rule rule = insertUnpairedRule(tag.getId());
        clearInvocations(actionExecutor);

        publishCompanyUpdated(company.getId());
        drainSchedulerWork();

        assertNull(workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId()));
        assertEquals(0, matchedExecutionCount(rule.getId()));
        assertTrue(companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId()).isEmpty());
        verifyNoInteractions(actionExecutor);
        assertEquals(
            0,
            count(
                "SELECT COUNT(*) FROM workflow_trigger_outbox WHERE workspace_id = ?",
                workspace.getId()));

        backfillTransaction.backfillWorkspace(null, workspace.getId());

        assertNotNull(workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId()));
    }

    private RuleTriggerListener synchronousListener() {
        Object target = AopProxyUtils.getSingletonTarget(ruleTriggerListener);
        if (target instanceof RuleTriggerListener listener) {
            return listener;
        }
        return ruleTriggerListener;
    }

    private RuleDto entityTagRule(int tagId) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        return rule(tagId, trigger, null);
    }

    private RuleDto scheduleTagRule(int tagId, SegmentDefinition condition) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        return rule(tagId, trigger, condition);
    }

    private RuleDto rule(int tagId, RuleTrigger trigger, SegmentDefinition condition) {
        RuleRequest request = new RuleRequest();
        request.setName("Legacy gate " + unique());
        request.setEnabled(true);
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setCondition(condition);
        request.setActions(List.of(addTag(tagId)));
        request.setExecutionMode("user");
        return ruleService.create(request);
    }

    private Rule insertUnpairedRule(int tagId) throws Exception {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        Rule rule = new Rule();
        rule.setWorkspaceId(workspace.getId());
        rule.setName("Unpaired gate " + unique());
        rule.setEnabled(true);
        rule.setRecordType("company");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(objectMapper.writeValueAsString(trigger));
        rule.setActionsJson(objectMapper.writeValueAsString(List.of(addTag(tagId))));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(currentUser.getId());
        rule.setCreatedById(currentUser.getId());
        ruleMapper.insert(rule);
        assertTrue(rule.getId() > 0);
        return rule;
    }

    private void publishCompanyUpdated(int companyId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> ruleTriggerPublisher.publish(
            workspace.getId(), "company", companyId, "company.updated"));
    }

    private WorkflowTriggerOutbox latestOutbox(int ruleId) {
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        if (workflow == null) {
            throw new AssertionError("Paired workflow is unavailable for rule " + ruleId);
        }
        Long outboxId = jdbcTemplate.queryForObject(
            "SELECT id FROM workflow_trigger_outbox"
                + " WHERE workspace_id = ? AND workflow_id = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            workspace.getId(),
            workflow.getId());
        if (outboxId == null) {
            throw new AssertionError("Durable outbox target was not inserted");
        }
        WorkflowTriggerOutbox outbox = outboxMapper.getById(workspace.getId(), outboxId);
        if (outbox == null) {
            throw new AssertionError("Durable outbox target was not readable");
        }
        return outbox;
    }

    private static WorkflowTriggerDispatch.EntityChange entityDispatch(
            WorkflowTriggerOutbox outbox) {
        return new WorkflowTriggerDispatch.EntityChange(
            outbox.getWorkspaceId(),
            outbox.getRecordType(),
            requireRecordId(outbox),
            outbox.getTriggerEvent(),
            outbox.getTriggerKey(),
            outbox.getOccurredAt().toInstant(ZoneOffset.UTC));
    }

    private static int requireRecordId(WorkflowTriggerOutbox outbox) {
        Integer recordId = outbox.getRecordId();
        if (recordId == null) {
            throw new AssertionError("Entity outbox target has no record id");
        }
        return recordId;
    }

    private void assertSingleLegacyEffect(RuleDto rule, int companyId, int tagId) {
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), rule.getId());
        assertNotNull(workflow);
        assertEquals(1, matchedExecutionCount(rule.getId()));
        assertEquals(0, workflowRunCount(rule.getId()));
        assertTrue(companyMapper.getCompaniesByTagId(workspace.getId(), tagId).stream()
            .anyMatch(company -> company.getId() == companyId));
        assertEquals(1, actionInvocationCount("company", companyId));
    }

    private int matchedExecutionCount(int ruleId) {
        return count(
            "SELECT COUNT(*) FROM rule_execution"
                + " WHERE workspace_id = ? AND rule_id = ? AND status = 'matched'",
            workspace.getId(), ruleId);
    }

    private int workflowRunCount(int ruleId) {
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspace.getId(), ruleId);
        if (workflow == null) {
            return 0;
        }
        return count(
            "SELECT COUNT(*) FROM workflow_run WHERE workspace_id = ? AND workflow_id = ?",
            workspace.getId(), workflow.getId());
    }

    private int actionInvocationCount(String recordType, int recordId) {
        return (int) mockingDetails(actionExecutor).getInvocations().stream()
            .filter(invocation -> "execute".equals(invocation.getMethod().getName()))
            .filter(invocation -> {
                Object argument = invocation.getArgument(1);
                return argument instanceof AutomationActionContext context
                    && recordType.equals(context.recordType())
                    && recordId == context.entityId();
            })
            .count();
    }

    private int actionInvocationCount(String recordType, List<Company> companies) {
        return companies.stream()
            .mapToInt(company -> actionInvocationCount(recordType, company.getId()))
            .sum();
    }

    private String outboxStatus(long outboxId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM workflow_trigger_outbox WHERE workspace_id = ? AND id = ?",
            String.class,
            workspace.getId(),
            outboxId);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
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

    private Company createCompany() {
        Company company = newCompany();
        createdCompanyIds.add(company.getId());
        return company;
    }

    private Company createCompany(String industry) {
        Company company = createCompany();
        company.setIndustry(industry);
        assertEquals(1, companyMapper.update(company));
        return company;
    }

    private Tag createTag() {
        Tag tag = newTag();
        createdTagIds.add(tag.getId());
        return tag;
    }

    private static RuleAction addTag(int tagId) {
        RuleAction action = new RuleAction();
        action.setType("add_tag");
        action.setTagId(tagId);
        return action;
    }

    private static SegmentDefinition fieldCondition(String field, String op, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(field);
        condition.setOp(op);
        condition.setValue(value);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
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
        for (int companyId : createdCompanyIds) {
            jdbcTemplate.update(
                "DELETE FROM company WHERE workspace_id = ? AND id = ?",
                workspace.getId(), companyId);
        }
        for (int tagId : createdTagIds) {
            tagMapper.delete(workspace.getId(), tagId);
        }
        workspaceMapper.removeMember(workspace.getId(), currentUser.getId());
        userMapper.delete(currentUser.getId());
    }

    private void drain(TableLifecycle declaration) {
        while (tenantTeardownTransaction.deleteBatch(
                workspace.getId(), declaration, 100) > 0) {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedDedupeConfiguration {

        @Bean
        @Primary
        WorkflowDedupeKey legacyGateWorkflowDedupeKey() {
            return new WorkflowDedupeKey(Clock.fixed(
                Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));
        }
    }
}
