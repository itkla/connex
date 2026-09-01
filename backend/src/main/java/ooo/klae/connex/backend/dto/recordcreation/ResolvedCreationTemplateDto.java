package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;

public record ResolvedCreationTemplateDto(
    String id,
    RecordCreationRecordType recordType,
    boolean system,
    int version,
    LocalizedTextDto name,
    LocalizedTextDto description,
    RecordCreationTemplateAvailability availability,
    List<ResolvedCreationGroupDto> groups,
    List<RecordCreationWarningDto> warnings
) {
}
