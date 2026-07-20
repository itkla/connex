package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class RuleDefinitionValidatorTest {

    @Mock private SegmentService segmentService;
    @Mock private WorkspaceService workspaceService;

    private RuleDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RuleDefinitionValidator(segmentService, workspaceService);
    }

    @Test
    void validDefinitionNormalizesSemanticsAndChecksActionPermission() {
        SegmentDefinition condition = condition();
        RuleRequest request = request(" Company ", schedule(" Daily "), " User ", action(" Add_Tag "));
        request.setCondition(condition);

        assertDoesNotThrow(() -> validator.validate(request));

        verify(segmentService).validate("company", condition);
        verify(workspaceService).requirePermission(Permission.COMPANY_UPDATE);
        verify(workspaceService, never()).requireRole(Role.ADMIN);
    }

    @Test
    void invalidTriggerPreservesExistingMessage() {
        RuleRequest request = request(
            "deal", entityChange("deal.stagechanged"), "user", action("notify"));

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals("Unsupported event for deal: deal.stagechanged", exception.getMessage());
    }

    @Test
    void invalidActionPreservesExistingMessage() {
        RuleAction action = new RuleAction();
        action.setType("add_tag");
        RuleRequest request = request("company", entityChange("company.updated"), "user", action);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals("A add_tag action requires a tagId", exception.getMessage());
    }

    @Test
    void emptyConditionPreservesExistingMessage() {
        SegmentDefinition empty = new SegmentDefinition();
        empty.setMatch("all");
        empty.setConditions(List.of());
        RuleRequest request = request("deal", entityChange("deal.won"), "user", action("notify"));
        request.setCondition(empty);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals("A WHEN condition must contain at least one condition", exception.getMessage());
        verify(segmentService, never()).validate("deal", empty);
    }

    @Test
    void semanticConditionFailureIsDelegatedWithoutTranslation() {
        SegmentDefinition condition = condition();
        RuleRequest request = request("company", entityChange("company.updated"), "user", action("notify"));
        request.setCondition(condition);
        doThrow(new BadRequestException("Unknown predicate: missing"))
            .when(segmentService).validate("company", condition);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals("Unknown predicate: missing", exception.getMessage());
    }

    @Test
    void systemModeRequiresAdminBeforeDefinitionIsAccepted() {
        RuleRequest request = request("deal", entityChange("deal.won"), "system", action("notify"));
        doThrow(new ForbiddenException("Requires admin role"))
            .when(workspaceService).requireRole(Role.ADMIN);

        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> validator.validate(request));

        assertEquals("Requires admin role", exception.getMessage());
        verify(workspaceService).requireRole(Role.ADMIN);
    }

    @Test
    void mutatingActionRequiresItsExecutionPermission() {
        RuleRequest request = request("deal", entityChange("deal.won"), "user", action("create_task"));
        doThrow(new ForbiddenException("Missing permission"))
            .when(workspaceService).requirePermission(Permission.TASK_CREATE);

        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> validator.validate(request));

        assertEquals("Missing permission", exception.getMessage());
        verify(workspaceService).requirePermission(Permission.TASK_CREATE);
    }

    @Test
    void previewNormalizesRecordTypeAndRejectsEmptyCondition() {
        RulePreviewRequest valid = new RulePreviewRequest();
        valid.setRecordType(" Company ");
        valid.setCondition(condition());
        assertEquals("company", validator.validatePreview(valid));

        RulePreviewRequest empty = new RulePreviewRequest();
        empty.setRecordType("company");
        empty.setCondition(new SegmentDefinition());
        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validatePreview(empty));
        assertEquals("A preview requires at least one condition", exception.getMessage());
    }

    private static RuleRequest request(
            String recordType, RuleTrigger trigger, String executionMode, RuleAction... actions) {
        RuleRequest request = new RuleRequest();
        request.setName("Rule");
        request.setRecordType(recordType);
        request.setTrigger(trigger);
        request.setActions(List.of(actions));
        request.setExecutionMode(executionMode);
        return request;
    }

    private static RuleTrigger schedule(String cadence) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType(" Schedule ");
        trigger.setCadence(cadence);
        return trigger;
    }

    private static RuleTrigger entityChange(String event) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of(event));
        return trigger;
    }

    private static RuleAction action(String type) {
        RuleAction action = new RuleAction();
        action.setType(type);
        switch (type.trim().toLowerCase()) {
            case "create_task", "notify" -> action.setTitle("title");
            case "add_tag" -> action.setTagId(1);
            default -> { }
        }
        return action;
    }

    private static SegmentDefinition condition() {
        SegmentCondition field = new SegmentCondition();
        field.setType("field");
        field.setField("name");
        field.setOp("contains");
        field.setValue("Acme");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(field));
        return condition;
    }
}
