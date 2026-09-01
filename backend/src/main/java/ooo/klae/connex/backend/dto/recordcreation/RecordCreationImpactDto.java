package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationImpactDto(
    RecordCreationImpactOperation operation,
    RecordCreationRecordType recordType,
    String templateId,
    boolean defaultTemplate,
    boolean enabledTemplate,
    List<String> removedFieldKeys,
    List<String> blockedRequiredFieldKeys,
    String nextSelectedTemplateId,
    int existingRecordsAffected,
    boolean requiresConfirmation
) {
}
