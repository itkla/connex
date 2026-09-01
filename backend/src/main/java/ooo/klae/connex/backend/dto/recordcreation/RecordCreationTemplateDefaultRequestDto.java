package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationTemplateDefaultRequestDto(
    @NotNull RecordCreationRecordType recordType,
    @NotBlank String templateId,
    @PositiveOrZero int expectedSetRevision
) {
}
