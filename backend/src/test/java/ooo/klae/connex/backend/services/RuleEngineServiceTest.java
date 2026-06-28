package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;

class RuleEngineServiceTest extends AbstractServiceTest {

    @Autowired RuleEngineService ruleEngineService;
    @Autowired RuleService ruleService;
    @Autowired RuleMapper ruleMapper;

    private static RuleAction addTag(int tagId) {
        RuleAction action = new RuleAction();
        action.setType("add_tag");
        action.setTagId(tagId);
        return action;
    }

    private static SegmentDefinition noActivity() {
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        SegmentCondition predicate = new SegmentCondition();
        predicate.setType("predicate");
        predicate.setKey("no_activity");
        predicate.setDays(30);
        definition.setConditions(List.of(predicate));
        return definition;
    }

    private RuleDto scheduleTagRule(int tagId, boolean system) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        RuleRequest request = new RuleRequest();
        request.setName("Tag stale " + unique());
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setCondition(noActivity());
        request.setActions(List.of(addTag(tagId)));
        request.setExecutionMode(system ? "system" : "user");
        return ruleService.create(request);
    }

    private RuleDto entityChangeTagRule(int tagId, String... events) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(events));
        RuleRequest request = new RuleRequest();
        request.setName("Tag on change " + unique());
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setActions(List.of(addTag(tagId)));
        request.setExecutionMode("user");
        return ruleService.create(request);
    }

    private boolean tagged(int companyId, int tagId) {
        return companyMapper.getCompaniesByTagId(workspace.getId(), tagId).stream()
            .anyMatch(company -> company.getId() == companyId);
    }

    private long matchedExecutions(int ruleId) {
        return ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50).stream()
            .filter(execution -> "matched".equals(execution.getStatus())).count();
    }

    @Test
    void schedule_tagsMatchingCompany_andRecordsExecution() {
        Company stale = newCompany();
        Tag tag = newTag();
        RuleDto rule = scheduleTagRule(tag.getId(), false);

        ruleEngineService.runSchedule(workspace.getId(), "daily");

        assertTrue(tagged(stale.getId(), tag.getId()));
        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void schedule_isIdempotentWithinTheDay() {
        Company stale = newCompany();
        Tag tag = newTag();
        RuleDto rule = scheduleTagRule(tag.getId(), false);

        ruleEngineService.runSchedule(workspace.getId(), "daily");
        ruleEngineService.runSchedule(workspace.getId(), "daily");

        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void schedule_wrongCadence_doesNotFire() {
        Company stale = newCompany();
        Tag tag = newTag();
        RuleDto rule = scheduleTagRule(tag.getId(), false);

        ruleEngineService.runSchedule(workspace.getId(), "weekly");

        assertFalse(tagged(stale.getId(), tag.getId()));
        assertEquals(0, matchedExecutions(rule.getId()));
    }

    @Test
    void entityChange_tagsTriggeringCompany() {
        Company company = newCompany();
        Tag tag = newTag();
        RuleDto rule = entityChangeTagRule(tag.getId(), "company.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertTrue(tagged(company.getId(), tag.getId()));
        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void entityChange_refiresOnEachOccurrence() {
        Company company = newCompany();
        Tag tag = newTag();
        RuleDto rule = entityChangeTagRule(tag.getId(), "company.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");
        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertEquals(2, matchedExecutions(rule.getId()));
    }

    @Test
    void entityChange_unlistedEvent_doesNotFire() {
        Company company = newCompany();
        Tag tag = newTag();
        RuleDto rule = entityChangeTagRule(tag.getId(), "company.created");

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertFalse(tagged(company.getId(), tag.getId()));
        assertEquals(0, matchedExecutions(rule.getId()));
    }

    @Test
    void systemMode_executesViaSystemActor() {
        Company stale = newCompany();
        Tag tag = newTag();
        RuleDto rule = scheduleTagRule(tag.getId(), true);

        ruleEngineService.runSchedule(workspace.getId(), "daily");

        assertTrue(tagged(stale.getId(), tag.getId()));
        assertEquals(1, matchedExecutions(rule.getId()));
    }
}
