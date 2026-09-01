package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

public record RecordCreationTemplateReorderRequestDto(
    @NotNull RecordCreationRecordType recordType,
    @NotNull List<String> orderedTemplateIds,
    @PositiveOrZero int expectedSetRevision
) {
}
