package ooo.klae.connex.backend.dto.recordcreation;

import java.time.Instant;
import java.util.List;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateStatus;

public record RecordCreationTemplateDto(
    String id,
    RecordCreationRecordType recordType,
    RecordCreationTemplateStatus status,
    boolean system,
    int position,
    int revision,
    int version,
    LocalizedTextDto name,
    LocalizedTextDto description,
    RecordCreationTemplateDefinitionDto definition,
    String definitionHash,
    boolean defaultTemplate,
    RecordCreationTemplateAvailability availability,
    List<RecordCreationWarningDto> warnings,
    Integer createdById,
    Integer updatedById,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt
) {
}
