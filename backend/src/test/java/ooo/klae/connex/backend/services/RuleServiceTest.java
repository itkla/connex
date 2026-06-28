package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class RuleServiceTest extends AbstractServiceTest {

    @Autowired RuleService ruleService;

    private static RuleTrigger schedule(String cadence) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence(cadence);
        return trigger;
    }

    private static RuleTrigger entityChange(String... events) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(events));
        return trigger;
    }

    private static RuleAction action(String type) {
        RuleAction action = new RuleAction();
        action.setType(type);
        switch (type) {
            case "create_task", "notify" -> action.setTitle("title");
            case "log_activity" -> action.setActivityType("note");
            case "add_tag" -> action.setTagId(1);
            default -> { }
        }
        return action;
    }

    private RuleRequest req(String recordType, RuleTrigger trigger, String mode, RuleAction... actions) {
        RuleRequest request = new RuleRequest();
        request.setName("Rule " + unique());
        request.setRecordType(recordType);
        request.setTrigger(trigger);
        request.setActions(List.of(actions));
        request.setExecutionMode(mode);
        return request;
    }

    @Test
    void create_roundTripsTriggerConditionActions() {
        RuleRequest request = req("company", schedule("daily"), "user", action("create_task"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentCondition predicate = new SegmentCondition();
        predicate.setType("predicate");
        predicate.setKey("no_activity");
        predicate.setDays(30);
        condition.setConditions(List.of(predicate));
        request.setCondition(condition);

        RuleDto fetched = ruleService.getById(ruleService.create(request).getId());

        assertEquals("schedule", fetched.getTrigger().getType());
        assertEquals("no_activity", fetched.getCondition().getConditions().get(0).getKey());
        assertEquals("create_task", fetched.getActions().get(0).getType());
        assertEquals(currentUser.getId(), fetched.getRunAsUserId());
    }

    @Test
    void create_systemMode_clearsRunAsUser() {
        RuleDto created = ruleService.create(req("deal", entityChange("deal.won"), "system", action("log_activity")));
        assertNull(created.getRunAsUserId());
        assertEquals("system", created.getExecutionMode());
    }

    @Test
    void create_invalidRecordType_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("bogus", schedule("daily"), "user", action("notify"))));
    }

    @Test
    void create_scheduleWithoutCadence_throws() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", trigger, "user", action("notify"))));
    }

    @Test
    void create_entityChangeWithoutEvents_throws() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("deal", trigger, "user", action("notify"))));
    }

    @Test
    void create_addTagWithoutTagId_throws() {
        RuleAction tagAction = new RuleAction();
        tagAction.setType("add_tag");
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", entityChange("company.updated"), "user", tagAction)));
    }

    @Test
    void create_conditionOnNonCompany_throws() {
        RuleRequest request = req("deal", entityChange("deal.won"), "user", action("notify"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of());
        request.setCondition(condition);
        assertThrows(BadRequestException.class, () -> ruleService.create(request));
    }

    @Test
    void update_replacesFields() {
        RuleDto created = ruleService.create(req("company", entityChange("company.created"), "user", action("notify")));
        RuleRequest update = req("company", entityChange("company.updated"), "user", action("add_tag"));
        update.setEnabled(false);
        RuleDto updated = ruleService.update(created.getId(), update);
        assertEquals("entity_change", updated.getTrigger().getType());
        assertEquals("add_tag", updated.getActions().get(0).getType());
        assertEquals(false, updated.isEnabled());
    }

    @Test
    void create_scheduleWithoutCondition_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", schedule("daily"), "user", action("notify"))));
    }

    @Test
    void delete_removesRule() {
        RuleDto created = ruleService.create(req("company", entityChange("company.updated"), "user", action("notify")));
        ruleService.delete(created.getId());
        assertThrows(ResourceNotFoundException.class, () -> ruleService.getById(created.getId()));
    }

    @Test
    void list_returnsCreatedRules() {
        int before = ruleService.list().size();
        ruleService.create(req("company", entityChange("company.updated"), "user", action("notify")));
        assertTrue(ruleService.list().size() >= before + 1);
    }
}
