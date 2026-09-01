package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationTemplateCreateRequestDto(
    @NotNull RecordCreationRecordType recordType,
    @NotNull @Valid LocalizedTextDto name,
    @Valid LocalizedTextDto description,
    @NotNull @Valid RecordCreationTemplateDefinitionDto definition,
    boolean enabled,
    @PositiveOrZero int expectedSetRevision
) {
}
