package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RulePreviewDto;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class RuleServiceTest extends AbstractServiceTest {

    @Autowired RuleService ruleService;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;

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
            case "add_tag", "remove_tag" -> action.setTagId(1);
            case "create_note" -> action.setBody("Automated note");
            case "assign_owner" -> action.setTargetUserId(1);
            case "change_stage" -> action.setTargetStageId(1);
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
    void update_byDifferentEditor_preservesCreatorRunAsUser() {
        int ruleId = ruleService.create(req("deal", entityChange("deal.won"), "user", action("notify"))).getId();
        assertEquals(currentUser.getId(), ruleService.getById(ruleId).getRunAsUserId());

        User editor = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), editor.getId(), "admin");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(editor, null, editor.getAuthorities()));

        ruleService.update(ruleId, req("deal", entityChange("deal.lost"), "user", action("notify")));

        assertEquals(currentUser.getId(), ruleService.getById(ruleId).getRunAsUserId(),
            "run-as must stay the original creator, not the editor");
    }

    @Test
    void create_actionPermissionGate_blocksUnprivilegedManager() {
        User restricted = newUser();
        WorkspaceRole ruleOnly = roleService.createRole(workspace.getId(), currentUser.getId(),
            "RuleOnly " + unique(), List.of("RULE_MANAGE"));
        workspaceService.assignCustomRole(workspace.getId(), currentUser.getId(), restricted.getId(), ruleOnly.getId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(restricted, null, restricted.getAuthorities()));

        assertThrows(ForbiddenException.class,
            () -> ruleService.create(req("deal", entityChange("deal.won"), "user", action("create_task"))));
        assertDoesNotThrow(
            () -> ruleService.create(req("deal", entityChange("deal.won"), "user", action("notify"))));
    }

    @Test
    void create_roundTripsTriggerConditionActions() {
        RuleRequest request = req("company", schedule("daily"), "user", action("add_tag"));
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
        assertEquals("add_tag", fetched.getActions().get(0).getType());
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
    void create_emptyConditionOnDealRule_throws() {
        RuleRequest request = req("deal", entityChange("deal.won"), "user", action("notify"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of());
        request.setCondition(condition);
        assertThrows(BadRequestException.class, () -> ruleService.create(request));
    }

    @Test
    void create_dealRuleWithFieldCondition_roundTrips() {
        RuleRequest request = req("deal", entityChange("deal.won"), "user", action("notify"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentCondition field = new SegmentCondition();
        field.setType("field");
        field.setField("value");
        field.setOp("gte");
        field.setValue("1000");
        condition.setConditions(List.of(field));
        request.setCondition(condition);

        RuleDto created = ruleService.create(request);

        assertEquals("value",
            ruleService.getById(created.getId()).getCondition().getConditions().get(0).getField());
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
    void create_unsupportedEvent_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("deal", entityChange("deal.stagechanged"), "user", action("notify"))));
    }

    @Test
    void create_linkedActionOnCompanyRule_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", entityChange("company.updated"), "user", action("create_task"))));
    }

    @Test
    void create_entityChangePerson_allowed() {
        assertDoesNotThrow(
            () -> ruleService.create(req("person", entityChange("person.updated"), "user", action("notify"))));
    }

    @Test
    void create_recordOwnerChangeEvents_allowed() {
        assertDoesNotThrow(() -> ruleService.create(
            req("company", entityChange("company.owner_changed"), "user", action("notify"))));
        assertDoesNotThrow(() -> ruleService.create(
            req("person", entityChange("person.owner_changed"), "user", action("notify"))));
    }

    @Test
    void create_entityChangeTask_allowed() {
        assertDoesNotThrow(
            () -> ruleService.create(req("task", entityChange("task.completed"), "user", action("notify"))));
    }

    @Test
    void create_unsupportedPersonEvent_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("person", entityChange("person.deleted"), "user", action("notify"))));
    }

    @Test
    void create_tagActionOnTaskRule_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("task", entityChange("task.created"), "user", action("add_tag"))));
    }

    @Test
    void create_scheduleWithoutCondition_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", schedule("daily"), "user", action("notify"))));
    }

    @Test
    void create_assignOwnerWithoutTarget_throws() {
        RuleAction bare = new RuleAction();
        bare.setType("assign_owner");
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("deal", entityChange("deal.stage_changed"), "user", bare)));
    }

    @Test
    void create_changeStageOnCompanyRule_throws() {
        assertThrows(BadRequestException.class,
            () -> ruleService.create(req("company", entityChange("company.updated"), "user", action("change_stage"))));
    }

    @Test
    void create_recordMutatingActionsOnDeal_roundTrip() {
        RuleDto created = ruleService.create(req("deal", entityChange("deal.won"), "user",
            action("assign_owner"), action("change_stage"), action("create_note")));
        assertEquals(3, created.getActions().size());
        assertEquals("assign_owner", created.getActions().get(0).getType());
    }

    @Test
    void delete_removesRule() {
        RuleDto created = ruleService.create(req("company", entityChange("company.updated"), "user", action("notify")));
        ruleService.delete(created.getId());
        assertThrows(ResourceNotFoundException.class, () -> ruleService.getById(created.getId()));
    }

    @Test
    void create_personRuleWithCompanyOnlyField_throws() {
        RuleRequest request = req("person", entityChange("person.updated"), "user", action("notify"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentCondition industry = new SegmentCondition();
        industry.setType("field");
        industry.setField("industry");
        industry.setOp("equals");
        industry.setValue("Tech");
        condition.setConditions(List.of(industry));
        request.setCondition(condition);

        assertThrows(BadRequestException.class, () -> ruleService.create(request));
    }

    @Test
    void create_emptyNestedGroup_throws() {
        RuleRequest request = req("company", entityChange("company.updated"), "user", action("notify"));
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentDefinition emptyGroup = new SegmentDefinition();
        emptyGroup.setMatch("all");
        emptyGroup.setConditions(List.of());
        condition.setGroups(List.of(emptyGroup));
        request.setCondition(condition);

        assertThrows(BadRequestException.class, () -> ruleService.create(request));
    }

    @Test
    void preview_returnsCountAndSample() {
        Company company = newCompany();
        RulePreviewRequest request = new RulePreviewRequest();
        request.setRecordType("company");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        SegmentCondition byName = new SegmentCondition();
        byName.setType("field");
        byName.setField("name");
        byName.setOp("contains");
        byName.setValue(company.getName());
        condition.setConditions(List.of(byName));
        request.setCondition(condition);

        RulePreviewDto preview = ruleService.preview(request);

        assertTrue(preview.getMatchCount() >= 1);
        assertTrue(preview.getSample().stream().anyMatch(record -> record.getId() == company.getId()));
    }

    @Test
    void preview_emptyCondition_throws() {
        RulePreviewRequest request = new RulePreviewRequest();
        request.setRecordType("company");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of());
        request.setCondition(condition);
        assertThrows(BadRequestException.class, () -> ruleService.preview(request));
    }

    @Test
    void list_returnsCreatedRules() {
        int before = ruleService.list().size();
        ruleService.create(req("company", entityChange("company.updated"), "user", action("notify")));
        assertTrue(ruleService.list().size() >= before + 1);
    }
}
