package ooo.klae.connex.backend.dto;

import java.util.List;

/** Server-owned schema and execution capability catalog for workflow authoring. */
public record WorkflowCatalogDto(
    int capabilityVersion,
    List<Integer> definitionSchemaVersions,
    int authoringSchemaVersion,
    List<RecordType> recordTypes,
    List<WorkflowInputType> inputTypes,
    List<RecordField> recordFields,
    List<Action> actions,
    String catchupPolicy,
    List<SupportedDateField> supportedDateFields
) {

    /** Supported workflow entry surfaces for one primary record type. */
    public record RecordType(String type, boolean manual, boolean event, boolean schedule) { }

    /** Safe current-record field exposed to typed bindings. */
    public record RecordField(String recordType, String key, String valueType, boolean nullable) { }

    /** One action capability for a stable record-type and permission contract. */
    public record Action(
        String type,
        List<String> recordTypes,
        List<String> requiredPermissions,
        String retrySafety,
        String sideEffect,
        List<ActionField> fields,
        List<ActionOutput> outputs
    ) { }

    /** One typed action configuration field. */
    public record ActionField(
        String key,
        String valueType,
        boolean required,
        List<String> bindingSources
    ) { }

    /** One typed artifact produced by an action. */
    public record ActionOutput(String key, String valueType) { }

    /** Date-trigger source currently supported by the runtime. */
    public record SupportedDateField(String recordType, String field) { }
}
