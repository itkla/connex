package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import ooo.klae.connex.backend.recordcreation.RecordCreationImpactOperation;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationImpactRequestDto(
    @NotNull RecordCreationImpactOperation operation,
    @NotNull RecordCreationRecordType recordType,
    String templateId,
    @NotNull List<@NotBlank String> removedFieldKeys,
    Integer expectedTemplateVersion,
    @PositiveOrZero int expectedSetRevision
) {
}
