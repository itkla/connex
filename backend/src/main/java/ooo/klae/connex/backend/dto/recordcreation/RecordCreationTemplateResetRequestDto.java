package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationTemplateResetRequestDto(
    @NotNull RecordCreationRecordType recordType,
    @PositiveOrZero int expectedSetRevision,
    boolean confirmImpact
) {
}
