package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class RuleDefinitionValidatorTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
        Validation.buildDefaultValidatorFactory();
    private static final Validator BEAN_VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @Mock private SegmentService segmentService;
    @Mock private WorkspaceService workspaceService;
    @Mock private SystemActor systemActor;

    private RuleDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        lenient().when(systemActor.permissions()).thenReturn(Set.of(
            Permission.TASK_CREATE,
            Permission.ACTIVITY_CREATE,
            Permission.NOTE_CREATE,
            Permission.COMPANY_UPDATE,
            Permission.PERSON_UPDATE,
            Permission.DEAL_UPDATE,
            Permission.CONSENT_MANAGE));
        validator = validatorWithDocumentFence(true);
    }

    private RuleDefinitionValidator validatorWithDocumentFence(boolean open) {
        return validatorWithFences(open, true);
    }

    private RuleDefinitionValidator validatorWithFences(
            boolean documentOpen, boolean triggeredSendOpen) {
        return new RuleDefinitionValidator(
            segmentService,
            workspaceService,
            BEAN_VALIDATOR,
            new WorkflowDocumentAutomationGate(documentOpen),
            new WorkflowTriggeredSendGate(triggeredSendOpen),
            systemActor);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
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
    void mutationValidationReturnsPermissionsWithoutReadingCurrentAuthorization() {
        RuleRequest request = request(
            "deal", entityChange("deal.won"), "system", action("create_task"));

        Set<Permission> required = validator.validateForMutation(request);

        assertEquals(Set.of(Permission.TASK_CREATE), required);
        verify(workspaceService, never()).requireRole(any());
        verify(workspaceService, never()).requirePermission(any());
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

    @Test
    void sharedServiceBoundaryRejectsNullTriggerAndActionCardinality() {
        RuleRequest missingTrigger = request(
            "deal", null, "user", action("notify"));
        BadRequestException triggerFailure = assertThrows(
            BadRequestException.class, () -> validator.validate(missingTrigger));
        assertEquals("Rule trigger is required", triggerFailure.getMessage());

        RuleRequest missingActions = request(
            "deal", entityChange("deal.won"), "user");
        BadRequestException emptyFailure = assertThrows(
            BadRequestException.class, () -> validator.validate(missingActions));
        assertEquals("A rule requires between 1 and 16 actions", emptyFailure.getMessage());

        RuleRequest tooManyActions = request(
            "deal",
            entityChange("deal.won"),
            "user",
            java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> action("notify"))
                .toArray(RuleAction[]::new));
        assertThrows(BadRequestException.class, () -> validator.validate(tooManyActions));

        RuleRequest nullAction = request(
            "deal", entityChange("deal.won"), "user", action("notify"));
        nullAction.setActions(java.util.Arrays.asList((RuleAction) null));
        BadRequestException nullFailure = assertThrows(
            BadRequestException.class, () -> validator.validate(nullAction));
        assertEquals("Rule action config is required", nullFailure.getMessage());
    }

    @Test
    void sharedServiceBoundaryEnforcesTriggerConstraintsBeforeSemanticValidation() {
        RuleTrigger oversizedEvent = entityChange("x".repeat(33));
        assertStructurallyInvalid(request("deal", oversizedEvent, "user", action("notify")));

        RuleTrigger tooManyEvents = entityChange("deal.won");
        tooManyEvents.setEvents(java.util.stream.IntStream.range(0, 9)
            .mapToObj(index -> "deal.won")
            .toList());
        assertStructurallyInvalid(request("deal", tooManyEvents, "user", action("notify")));

        RuleTrigger belowMinimum = entityChange("deal.won");
        belowMinimum.setThrottleMinutes(0);
        assertStructurallyInvalid(request("deal", belowMinimum, "user", action("notify")));

        RuleTrigger aboveMaximum = entityChange("deal.won");
        aboveMaximum.setThrottleMinutes(10081);
        assertStructurallyInvalid(request("deal", aboveMaximum, "user", action("notify")));

        verify(segmentService, never()).validate(any(), any());
        verify(workspaceService, never()).requirePermission(any());
    }

    @Test
    void sharedServiceBoundaryRecursivelyEnforcesSegmentConstraintsAndNestedValues() {
        SegmentCondition invalidValue = condition().getConditions().getFirst();
        invalidValue.setValue("x".repeat(256));
        SegmentDefinition nested = new SegmentDefinition();
        nested.setMatch("all");
        nested.setConditions(List.of(invalidValue));
        SegmentDefinition root = new SegmentDefinition();
        root.setMatch("all");
        root.setGroups(List.of(nested));
        RuleRequest oversizedNestedValue = request(
            "company", entityChange("company.updated"), "user", action("notify"));
        oversizedNestedValue.setCondition(root);
        assertStructurallyInvalid(oversizedNestedValue);

        SegmentCondition invalidContainerValue = condition().getConditions().getFirst();
        invalidContainerValue.setValues(List.of("valid", ""));
        SegmentDefinition invalidValues = new SegmentDefinition();
        invalidValues.setMatch("all");
        invalidValues.setConditions(List.of(invalidContainerValue));
        RuleRequest blankNestedValue = request(
            "company", entityChange("company.updated"), "user", action("notify"));
        blankNestedValue.setCondition(invalidValues);
        assertStructurallyInvalid(blankNestedValue);

        SegmentDefinition nullNestedGroup = new SegmentDefinition();
        nullNestedGroup.setMatch("all");
        nullNestedGroup.setGroups(java.util.Arrays.asList((SegmentDefinition) null));
        RuleRequest nullGroup = request(
            "company", entityChange("company.updated"), "user", action("notify"));
        nullGroup.setCondition(nullNestedGroup);
        assertStructurallyInvalid(nullGroup);

        verify(segmentService, never()).validate(any(), any());
        verify(workspaceService, never()).requirePermission(any());
    }

    @Test
    void sharedServiceBoundaryEnforcesEveryActionConstraint() {
        RuleAction oversizedTitle = action("notify");
        oversizedTitle.setTitle("x".repeat(256));
        assertStructurallyInvalid(request(
            "deal", entityChange("deal.won"), "user", oversizedTitle));

        RuleAction oversizedBody = action("create_note");
        oversizedBody.setBody("x".repeat(2001));
        assertStructurallyInvalid(request(
            "deal", entityChange("deal.won"), "user", oversizedBody));

        RuleAction missingType = action("notify");
        missingType.setType(null);
        assertStructurallyInvalid(request(
            "deal", entityChange("deal.won"), "user", missingType));

        verify(workspaceService, never()).requirePermission(any());
    }

    @Test
    void documentEntityChangeRuleIsAccepted() {
        RuleRequest request = request(
            " Document ", entityChange("document.approved"), "user", action("notify"));

        assertDoesNotThrow(() -> validator.validate(request));

        verify(segmentService, never()).validate(any(), any());
    }

    @Test
    void documentRuleRejectsUnknownEvent() {
        RuleRequest request = request(
            "document", entityChange("document.sent"), "user", action("notify"));

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals("Unsupported event for document: document.sent", exception.getMessage());
    }

    @Test
    void documentRuleRejectsCondition() {
        RuleRequest request = request(
            "document", entityChange("document.approved"), "user", action("notify"));
        request.setCondition(condition());

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals(
            "WHEN conditions are not supported for record type: document", exception.getMessage());
        verify(segmentService, never()).validate(any(), any());
    }

    @Test
    void documentRuleRejectsScheduleTrigger() {
        RuleRequest request = request("document", schedule("daily"), "user", action("notify"));
        request.setCondition(condition());

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> validator.validate(request));

        assertEquals(
            "WHEN conditions are not supported for record type: document", exception.getMessage());

        RuleRequest withoutCondition = request(
            "document", schedule("daily"), "user", action("notify"));

        BadRequestException scheduleFailure = assertThrows(BadRequestException.class,
            () -> validator.validate(withoutCondition));

        assertEquals(
            "Schedule rules are not supported for record type: document",
            scheduleFailure.getMessage());
    }

    @Test
    void documentRuleRejectsChangeStageAndTagActions() {
        for (String type : List.of("change_stage", "assign_owner", "add_tag", "remove_tag")) {
            RuleRequest request = request(
                "document", entityChange("document.approved"), "user", action(type));

            BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validate(request),
                () -> type + " must not be available to document rules");

            assertEquals(
                "'" + type + "' actions are not supported for document rules",
                exception.getMessage());
        }
    }

    @Test
    void documentRuleAcceptsCreateTaskLogActivityCreateNoteAndNotify() {
        RuleRequest request = request(
            "document",
            entityChange("document.finalized"),
            "user",
            action("create_task"), action("log_activity"), action("create_note"), action("notify"));

        Set<Permission> required = validator.validateForMutation(request);

        assertEquals(
            Set.of(Permission.TASK_CREATE, Permission.ACTIVITY_CREATE, Permission.NOTE_CREATE),
            required);
    }

    @Test
    void closedDocumentFenceRefusesEveryDocumentDefinition() {
        RuleDefinitionValidator fenced = validatorWithDocumentFence(false);

        for (RuleAction action : List.of(
                action("notify"), action("create_task"),
                action("log_activity"), action("create_note"))) {
            RuleRequest request = request(
                "document", entityChange("document.approved"), "user", action);

            BadRequestException exception = assertThrows(BadRequestException.class,
                () -> fenced.validateForMutation(request),
                () -> action.getType() + " must stay fenced until the deployment opens it");

            assertEquals("Invalid record type: document", exception.getMessage());
        }

        verify(workspaceService, never()).requirePermission(any());
    }

    @Test
    void closedDocumentFenceLeavesEveryOtherRecordTypeAuthorable() {
        RuleDefinitionValidator fenced = validatorWithDocumentFence(false);

        for (String recordType : List.of("deal", "company", "person", "task")) {
            RuleRequest request = request(
                recordType, entityChange(recordType + ".created"), "user", action("notify"));

            assertDoesNotThrow(
                () -> fenced.validateForMutation(request),
                () -> recordType + " must remain authorable behind the document fence");
        }
    }

    @Test
    void sendMessageIsPersonOnlyAndRequiresTheCompletePermissionSet() {
        RuleAction send = action("send_message");
        RuleRequest person = request(
            "person", entityChange("person.updated"), "user", send);

        assertEquals(
            Set.of(
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE),
            validator.validateForMutation(person));
        assertDoesNotThrow(() -> validator.validate(person));
        verify(workspaceService).requirePermission(Permission.CAMPAIGN_MANAGE);
        verify(workspaceService).requirePermission(Permission.CAMPAIGN_SEND);
        verify(workspaceService).requirePermission(Permission.CONSENT_MANAGE);

        for (String recordType : List.of("company", "deal", "task", "document")) {
            RuleRequest unsupported = request(
                recordType,
                entityChange(switch (recordType) {
                    case "company" -> "company.updated";
                    case "deal" -> "deal.won";
                    case "task" -> "task.completed";
                    case "document" -> "document.approved";
                    default -> throw new IllegalStateException();
                }),
                "user",
                send);
            WorkflowDefinitionValidationException exception = assertThrows(
                WorkflowDefinitionValidationException.class,
                () -> validator.validateForMutation(unsupported));
            assertEquals(
                ooo.klae.connex.backend.dto.WorkflowDiagnosticCode.ACTION_RECORD_TYPE_UNSUPPORTED,
                exception.diagnostic().code());
        }
    }

    @Test
    void sendMessageRequiresBothRevisionIdentityFields() {
        RuleAction missingMessage = action("send_message");
        missingMessage.setCampaignMessageId(null);
        WorkflowDefinitionValidationException messageFailure = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> validator.validateForMutation(request(
                "person", entityChange("person.updated"), "user", missingMessage)));

        RuleAction missingVersion = action("send_message");
        missingVersion.setCampaignMessageVersion(null);
        WorkflowDefinitionValidationException versionFailure = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> validator.validateForMutation(request(
                "person", entityChange("person.updated"), "user", missingVersion)));

        assertEquals("config.campaignMessageId", messageFailure.diagnostic().fieldPath());
        assertEquals("config.campaignMessageVersion", versionFailure.diagnostic().fieldPath());
    }

    @Test
    void triggeredSendFenceRejectsTheActionWithoutBlockingOtherActions() {
        RuleDefinitionValidator fenced = validatorWithFences(true, false);

        WorkflowDefinitionValidationException exception = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> fenced.validateForMutation(request(
                "person", entityChange("person.updated"), "user", action("send_message"))));

        assertEquals(
            ooo.klae.connex.backend.dto.WorkflowDiagnosticCode.ACTION_TYPE_INVALID,
            exception.diagnostic().code());
        assertDoesNotThrow(() -> fenced.validateForMutation(request(
            "person", entityChange("person.updated"), "user", action("create_task"))));
    }

    @Test
    void systemModeCannotPublishSendMessageWithoutSystemActorPermissions() {
        WorkflowDefinitionValidationException exception = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> validator.validateForMutation(request(
                "person", entityChange("person.updated"), "system", action("send_message"))));

        assertEquals(
            ooo.klae.connex.backend.dto.WorkflowDiagnosticCode.ACTION_SYSTEM_PERMISSION_MISSING,
            exception.diagnostic().code());
    }

    @Test
    void systemModeCannotSaveSendMessageDraftWithoutSystemActorPermissions() {
        WorkflowDefinitionValidationException exception = assertThrows(
            WorkflowDefinitionValidationException.class,
            () -> validator.validateDraftActionsForMutation(
                "person",
                List.of(new WorkflowNode.Action("send", action("send_message"))),
                "system"));

        assertEquals(
            ooo.klae.connex.backend.dto.WorkflowDiagnosticCode.ACTION_SYSTEM_PERMISSION_MISSING,
            exception.diagnostic().code());
    }

    private void assertStructurallyInvalid(RuleRequest request) {
        BadRequestException exception = assertThrows(
            BadRequestException.class, () -> validator.validate(request));
        assertEquals("Rule definition is invalid", exception.getMessage());
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
            case "log_activity" -> action.setActivityType("call");
            case "create_note" -> action.setBody("body");
            case "send_message" -> {
                action.setCampaignMessageId(17);
                action.setCampaignMessageVersion(3);
            }
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
