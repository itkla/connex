package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultOrigin;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldSource;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldValueType;

public record ResolvedCreationFieldDto(
    String key,
    RecordCreationFieldSource source,
    Integer customFieldId,
    RecordCreationFieldValueType valueType,
    String schemaFingerprint,
    LocalizedTextDto label,
    LocalizedTextDto helpText,
    LocalizedTextDto placeholder,
    boolean required,
    boolean schemaRequired,
    boolean protectedField,
    JsonNode defaultValue,
    RecordCreationDefaultOrigin defaultOrigin,
    List<CreationFieldOptionDto> options
) {
}
