package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.dto.WorkflowCatalogDto;
import ooo.klae.connex.backend.dto.WorkflowInputType;
import ooo.klae.connex.backend.tenant.Permission;

/** Authoritative workflow schema, binding, permission, and retry capability catalog. */
@Service
public class WorkflowCapabilityCatalog {

    private static final List<String> TEXT_BINDINGS = List.of(
        "literal", "launch_input", "record_field", "step_output");
    private static final List<String> USER_BINDINGS = List.of(
        "literal", "launch_input", "record_field");
    private static final List<String> DATE_BINDINGS = List.of(
        "relative_days", "launch_input", "record_field");
    private static final List<WorkflowCatalogDto.Action> ACTIONS = actions();

    public WorkflowCatalogDto catalog() {
        return new WorkflowCatalogDto(
            1,
            List.of(1, 2),
            2,
            List.of(
                new WorkflowCatalogDto.RecordType("person", true, true, true),
                new WorkflowCatalogDto.RecordType("company", true, true, true),
                new WorkflowCatalogDto.RecordType("deal", true, true, true)),
            List.of(WorkflowInputType.TEXT, WorkflowInputType.USER, WorkflowInputType.DATE),
            recordFields(),
            ACTIONS,
            "latest_occurrence",
            List.of(new WorkflowCatalogDto.SupportedDateField("deal", "expectedCloseDate")));
    }

    boolean supports(String actionType, String recordType) {
        return ACTIONS.stream().anyMatch(action -> action.type().equals(actionType)
            && action.recordTypes().contains(recordType));
    }

    Set<Permission> permissions(String actionType, String recordType) {
        return ACTIONS.stream()
            .filter(action -> action.type().equals(actionType)
                && action.recordTypes().contains(recordType))
            .findFirst()
            .map(action -> action.requiredPermissions().stream()
                .map(Permission::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()))
            .orElse(Set.of());
    }

    String retrySafety(String actionType, String recordType) {
        return ACTIONS.stream()
            .filter(action -> action.type().equals(actionType)
                && action.recordTypes().contains(recordType))
            .findFirst()
            .map(WorkflowCatalogDto.Action::retrySafety)
            .orElse("none");
    }

    boolean supportsRecordField(String recordType, String field) {
        return recordFields().stream().anyMatch(candidate -> candidate.recordType().equals(recordType)
            && candidate.key().equals(field));
    }

    String recordFieldType(String recordType, String field) {
        return recordFields().stream()
            .filter(candidate -> candidate.recordType().equals(recordType)
                && candidate.key().equals(field))
            .findFirst()
            .map(WorkflowCatalogDto.RecordField::valueType)
            .orElse(null);
    }

    private static List<WorkflowCatalogDto.RecordField> recordFields() {
        List<WorkflowCatalogDto.RecordField> fields = new ArrayList<>();
        for (String recordType : List.of("person", "company", "deal")) {
            fields.add(new WorkflowCatalogDto.RecordField(recordType, "id", "integer", false));
            fields.add(new WorkflowCatalogDto.RecordField(recordType, "name", "text", false));
            fields.add(new WorkflowCatalogDto.RecordField(recordType, "ownerId", "user", true));
        }
        fields.add(new WorkflowCatalogDto.RecordField("person", "email", "text", true));
        fields.add(new WorkflowCatalogDto.RecordField("person", "title", "text", true));
        fields.add(new WorkflowCatalogDto.RecordField("person", "companyId", "integer", true));
        fields.add(new WorkflowCatalogDto.RecordField("person", "companyName", "text", true));
        fields.add(new WorkflowCatalogDto.RecordField("company", "industry", "text", true));
        fields.add(new WorkflowCatalogDto.RecordField("deal", "status", "text", false));
        fields.add(new WorkflowCatalogDto.RecordField("deal", "expectedCloseDate", "date", true));
        fields.add(new WorkflowCatalogDto.RecordField("deal", "companyId", "integer", true));
        fields.add(new WorkflowCatalogDto.RecordField("deal", "companyName", "text", true));
        return List.copyOf(fields);
    }

    private static List<WorkflowCatalogDto.Action> actions() {
        List<WorkflowCatalogDto.Action> actions = new ArrayList<>();
        actions.add(action("create_task", List.of("person", "company", "deal"),
            List.of("TASK_CREATE"), "transactional", "task_created",
            List.of(
                field("title", "text", true, TEXT_BINDINGS),
                field("targetUser", "user", true, USER_BINDINGS),
                field("dueDate", "date", true, DATE_BINDINGS)),
            List.of(new WorkflowCatalogDto.ActionOutput("taskId", "integer"))));
        actions.add(action("log_activity", List.of("person", "deal"),
            List.of("ACTIVITY_CREATE"), "transactional", "activity_created",
            List.of(field("title", "text", true, TEXT_BINDINGS)), List.of()));
        actions.add(action("create_note", List.of("person", "deal"),
            List.of("NOTE_CREATE"), "transactional", "note_created",
            List.of(field("body", "text", true, TEXT_BINDINGS)), List.of()));
        for (String recordType : List.of("company", "person", "deal")) {
            String permission = switch (recordType) {
                case "company" -> "COMPANY_UPDATE";
                case "person" -> "PERSON_UPDATE";
                default -> "DEAL_UPDATE";
            };
            actions.add(action("add_tag", List.of(recordType), List.of(permission),
                "transactional", "tag_added", List.of(), List.of()));
            actions.add(action("remove_tag", List.of(recordType), List.of(permission),
                "transactional", "tag_removed", List.of(), List.of()));
            actions.add(action("assign_owner", List.of(recordType), List.of(permission),
                "transactional", "owner_assigned",
                List.of(field("targetUser", "user", true, USER_BINDINGS)), List.of()));
        }
        actions.add(action("set_response_due", List.of("person"), List.of("PERSON_UPDATE"),
            "transactional", "response_due_set", List.of(), List.of()));
        actions.add(action("change_stage", List.of("deal"), List.of("DEAL_UPDATE"),
            "transactional", "stage_changed", List.of(), List.of()));
        actions.add(action("update_field", List.of("deal"), List.of("DEAL_UPDATE"),
            "transactional", "field_updated",
            List.of(field("expectedCloseDate", "date", true,
                List.of("literal", "launch_input", "record_field"))), List.of()));
        actions.add(action("notify", List.of("company", "person", "deal"), List.of(),
            "deduplicated", "notification_sent",
            List.of(
                field("title", "text", true, TEXT_BINDINGS),
                field("body", "text", false, TEXT_BINDINGS),
                field("targetUser", "user", true, USER_BINDINGS)), List.of()));
        actions.add(action("send_message", List.of("person"),
            List.of("CAMPAIGN_MANAGE", "CAMPAIGN_SEND", "CONSENT_MANAGE"),
            "deduplicated", "message_enrolled", List.of(), List.of()));
        return List.copyOf(actions);
    }

    private static WorkflowCatalogDto.Action action(
            String type,
            List<String> recordTypes,
            List<String> permissions,
            String retrySafety,
            String sideEffect,
            List<WorkflowCatalogDto.ActionField> fields,
            List<WorkflowCatalogDto.ActionOutput> outputs) {
        return new WorkflowCatalogDto.Action(
            type, recordTypes, permissions, retrySafety, sideEffect, fields, outputs);
    }

    private static WorkflowCatalogDto.ActionField field(
            String key, String valueType, boolean required, List<String> bindingSources) {
        return new WorkflowCatalogDto.ActionField(key, valueType, required, bindingSources);
    }
}
