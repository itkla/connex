package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationPresetCatalogDto(
    RecordCreationRecordType recordType,
    RecordCreationEntryPoint entryPoint,
    int setRevision,
    String selectedTemplateId,
    List<ResolvedCreationTemplateDto> templates,
    boolean partial,
    List<RecordCreationWarningDto> warnings
) {
}
