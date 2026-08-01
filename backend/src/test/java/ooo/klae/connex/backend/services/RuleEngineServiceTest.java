package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
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
    void entityChange_normalizesActionType() {
        Company company = newCompany();
        Tag tag = newTag();
        RuleAction upper = new RuleAction();
        upper.setType("ADD_TAG");
        upper.setTagId(tag.getId());
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        RuleRequest request = new RuleRequest();
        request.setName("Upper " + unique());
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setActions(List.of(upper));
        request.setExecutionMode("user");
        ruleService.create(request);

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertTrue(tagged(company.getId(), tag.getId()));
    }

    @Test
    void entityChange_targetStageId_firesOnlyForMatchingStage() {
        Pipeline pipeline = newPipeline();
        Stage stageA = newStage(pipeline, 0);
        Stage stageB = newStage(pipeline, 1);
        Company company = newCompany();
        Deal dealInA = newDeal(pipeline, stageA, company);
        Deal dealInB = newDeal(pipeline, stageB, company);
        Tag tag = newTag();
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.stage_changed"));
        trigger.setTargetStageId(stageA.getId());
        RuleRequest request = new RuleRequest();
        request.setName("Stage A " + unique());
        request.setRecordType("deal");
        request.setTrigger(trigger);
        request.setActions(List.of(addTag(tag.getId())));
        request.setExecutionMode("user");
        RuleDto rule = ruleService.create(request);

        ruleEngineService.onEntityChange(workspace.getId(), "deal", dealInA.getId(), "deal.stage_changed");
        ruleEngineService.onEntityChange(workspace.getId(), "deal", dealInB.getId(), "deal.stage_changed");

        assertTrue(ruleMapper.getExecutionsByRule(workspace.getId(), rule.getId(), 50).stream()
            .anyMatch(e -> e.getTriggerEntityId() == dealInA.getId() && "matched".equals(e.getStatus())));
        assertFalse(ruleMapper.getExecutionsByRule(workspace.getId(), rule.getId(), 50).stream()
            .anyMatch(e -> e.getTriggerEntityId() == dealInB.getId()));
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

    private static RuleAction notifyAction() {
        RuleAction action = new RuleAction();
        action.setType("notify");
        action.setTitle("Automated");
        return action;
    }

    private RuleDto entityChangeRule(String recordType, RuleAction action, SegmentDefinition condition, String... events) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(events));
        RuleRequest request = new RuleRequest();
        request.setName("Rule " + unique());
        request.setRecordType(recordType);
        request.setTrigger(trigger);
        request.setCondition(condition);
        request.setActions(List.of(action));
        request.setExecutionMode("user");
        return ruleService.create(request);
    }

    private boolean firedFor(int ruleId, int entityId) {
        return ruleMapper.getExecutionsByRule(workspace.getId(), ruleId, 50).stream()
            .anyMatch(e -> e.getTriggerEntityId() == entityId && "matched".equals(e.getStatus()));
    }

    @Test
    void entityChange_dealWhenCondition_filtersByField() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal big = newDeal(pipeline, stage, company);
        Deal small = newDeal(pipeline, stage, company);
        small.setValue(new BigDecimal("100.00"));
        dealMapper.updateValueAndSource(
            workspace.getId(), small.getId(), small.getValue(), "manual");
        Tag tag = newTag();

        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentCondition value = new SegmentCondition();
        value.setType("field");
        value.setField("value");
        value.setOp("gte");
        value.setValue("500");
        condition.setConditions(List.of(value));
        RuleAction tagIt = new RuleAction();
        tagIt.setType("add_tag");
        tagIt.setTagId(tag.getId());
        RuleDto rule = entityChangeRule("deal", tagIt, condition, "deal.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "deal", big.getId(), "deal.updated");
        ruleEngineService.onEntityChange(workspace.getId(), "deal", small.getId(), "deal.updated");

        assertTrue(firedFor(rule.getId(), big.getId()));
        assertFalse(firedFor(rule.getId(), small.getId()));
    }

    @Test
    void entityChange_person_fires() {
        Person person = newPerson(newCompany());
        RuleDto rule = entityChangeRule("person", notifyAction(), null, "person.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "person", person.getId(), "person.updated");

        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void entityChange_suspendedPerson_doesNotFire() {
        Person person = newPerson(newCompany());
        RuleDto rule = entityChangeRule("person", notifyAction(), null, "person.updated");
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, false);

        ruleEngineService.onEntityChange(workspace.getId(), "person", person.getId(), "person.updated");

        assertEquals(0, matchedExecutions(rule.getId()));
    }

    @Test
    void entityChange_task_fires() {
        Task task = newTask(currentUser, newPerson(newCompany()), null);
        RuleDto rule = entityChangeRule("task", notifyAction(), null, "task.completed");

        ruleEngineService.onEntityChange(workspace.getId(), "task", task.getId(), "task.completed");

        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void throttle_collapsesRepeatFiresWithinWindow() {
        Company company = newCompany();
        Tag tag = newTag();
        RuleAction tagIt = new RuleAction();
        tagIt.setType("add_tag");
        tagIt.setTagId(tag.getId());
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("company.updated"));
        trigger.setThrottleMinutes(60);
        RuleRequest request = new RuleRequest();
        request.setName("Throttled " + unique());
        request.setRecordType("company");
        request.setTrigger(trigger);
        request.setActions(List.of(tagIt));
        request.setExecutionMode("user");
        RuleDto rule = ruleService.create(request);

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");
        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void assignOwner_action_reassignsDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        User newOwner = newUser();
        RuleAction assign = new RuleAction();
        assign.setType("assign_owner");
        assign.setTargetUserId(newOwner.getId());
        RuleDto rule = entityChangeRule("deal", assign, null, "deal.won");

        ruleEngineService.onEntityChange(workspace.getId(), "deal", deal.getId(), "deal.won");

        assertEquals(1, matchedExecutions(rule.getId()));
        assertEquals(newOwner.getId(), dealMapper.getDealById(workspace.getId(), deal.getId()).getOwnerId());
    }

    @Test
    void changeStage_action_movesDeal() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, from, newCompany());
        RuleAction move = new RuleAction();
        move.setType("change_stage");
        move.setTargetStageId(to.getId());
        RuleDto rule = entityChangeRule("deal", move, null, "deal.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "deal", deal.getId(), "deal.updated");

        assertEquals(1, matchedExecutions(rule.getId()));
        assertEquals(to.getId(), dealMapper.getDealById(workspace.getId(), deal.getId()).getStageId());
    }

    @Test
    void createNote_action_succeeds() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        RuleAction note = new RuleAction();
        note.setType("create_note");
        note.setBody("Automated follow-up");
        RuleDto rule = entityChangeRule("deal", note, null, "deal.won");

        ruleEngineService.onEntityChange(workspace.getId(), "deal", deal.getId(), "deal.won");

        assertEquals(1, matchedExecutions(rule.getId()));
    }

    @Test
    void removeTag_action_untagsCompany() {
        Company company = newCompany();
        Tag tag = newTag();
        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());
        RuleAction remove = new RuleAction();
        remove.setType("remove_tag");
        remove.setTagId(tag.getId());
        RuleDto rule = entityChangeRule("company", remove, null, "company.updated");

        ruleEngineService.onEntityChange(workspace.getId(), "company", company.getId(), "company.updated");

        assertEquals(1, matchedExecutions(rule.getId()));
        assertFalse(tagged(company.getId(), tag.getId()));
    }
}
