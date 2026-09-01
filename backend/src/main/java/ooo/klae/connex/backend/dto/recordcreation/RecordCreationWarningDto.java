package ooo.klae.connex.backend.dto.recordcreation;

public record RecordCreationWarningDto(
    String code,
    String templateId,
    String fieldKey,
    Integer customFieldId
) {
}
