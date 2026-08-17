package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.mappers.RuleMapper;

/**
 * The acceptance path increment 4b exists for (#559): a deadline passes, the sweep escalates it
 * through the real rule engine, and a workspace's authored rule acts on the lead — once.
 *
 * <p>This is deliberately separate from {@code LeadResponseSlaServiceTest}, which mocks the trigger
 * publisher to isolate the clock's own guards. Here the publisher is real, so the test proves the
 * sweep's tenant scope actually emits {@code person.first_response_overdue} rather than having it
 * swallowed by the automation-loop guard — the failure mode {@code runAsObserver} exists to prevent,
 * and one a mock can never catch.
 */
@Import(LeadResponseSlaEscalationTest.TriggerRecorder.class)
class LeadResponseSlaEscalationTest extends AbstractServiceTest {

    @Autowired LeadResponseSlaService slaService;
    @Autowired LeadResponseSlaScheduler slaScheduler;
    @Autowired RuleService ruleService;
    @Autowired RuleEngineService ruleEngineService;
    @Autowired RuleMapper ruleMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TriggerRecorder recorder;

    @BeforeEach
    void clearRecordedTriggers() {
        recorder.events.clear();
    }

    @Test
    void aBreachEscalatesThroughAnAuthoredRuleExactlyOnce() {
        Person lead = newPerson(newCompany());
        User escalationOwner = newUser();
        assertNull(personMapper.getPersonById(workspace.getId(), lead.getId()).getOwnerId());
        RuleDto rule = escalationRule(escalationOwner);
        slaService.startFirstResponseClock(lead.getId(), 4);
        expireDeadline(lead);

        slaScheduler.sweepBatches(workspace.getId());

        List<RuleTriggerEvent> escalations = recorder.forEvent("person.first_response_overdue");
        assertEquals(1, escalations.size(),
            "the sweep must publish the breach; a swallowed trigger means no escalation exists");
        assertEquals(lead.getId(), escalations.getFirst().entityId());

        ruleEngineService.onEntityChange(
            workspace.getId(), "person", lead.getId(), "person.first_response_overdue");

        assertEquals(1, matchedExecutions(rule.getId()));
        assertEquals(escalationOwner.getId(),
            personMapper.getPersonById(workspace.getId(), lead.getId()).getOwnerId());

        recorder.events.clear();
        slaScheduler.sweepBatches(workspace.getId());
        assertTrue(recorder.forEvent("person.first_response_overdue").isEmpty(),
            "a second sweep must not escalate an already-breached lead");
    }

    private RuleDto escalationRule(User escalationOwner) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.first_response_overdue"));
        RuleAction assign = new RuleAction();
        assign.setType("assign_owner");
        assign.setTargetUserId(escalationOwner.getId());
        RuleRequest request = new RuleRequest();
        request.setName("Escalate overdue " + unique());
        request.setRecordType("person");
        request.setTrigger(trigger);
        request.setActions(List.of(assign));
        request.setExecutionMode("user");
        return ruleService.create(request);
    }

    private long matchedExecutions(int ruleId) {
        return ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50).stream()
            .filter(execution -> "matched".equals(execution.getStatus()))
            .count();
    }

    private void expireDeadline(Person person) {
        jdbcTemplate.update(
            "UPDATE person SET first_response_due_at = ? WHERE workspace_id = ? AND id = ?",
            java.sql.Timestamp.valueOf("2020-01-01 00:00:00"), workspace.getId(), person.getId());
    }

    /** Collects rule-trigger events synchronously, so the assertion never races the async listener. */
    @TestConfiguration
    @Component
    static class TriggerRecorder {

        private final List<RuleTriggerEvent> events = new ArrayList<>();

        @EventListener
        void onTrigger(RuleTriggerEvent event) {
            events.add(event);
        }

        List<RuleTriggerEvent> forEvent(String event) {
            return events.stream().filter(candidate -> event.equals(candidate.event())).toList();
        }
    }
}
