package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationTemplatePreviewRequestDto(
    String templateId,
    @NotNull RecordCreationRecordType recordType,
    @Valid LocalizedTextDto name,
    @Valid LocalizedTextDto description,
    @Valid RecordCreationTemplateDefinitionDto definition,
    @Pattern(regexp = "^(en|ja)$") String locale,
    @Pattern(regexp = "^(desktop|mobile)$") String viewport,
    @Valid RecordCreationContextDto context
) {
}
