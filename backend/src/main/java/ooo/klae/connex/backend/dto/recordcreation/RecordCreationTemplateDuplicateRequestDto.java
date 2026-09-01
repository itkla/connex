package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record RecordCreationTemplateDuplicateRequestDto(
    @NotNull @Valid LocalizedTextDto name,
    @Valid LocalizedTextDto description,
    @Positive int expectedSourceVersion,
    @PositiveOrZero int expectedSetRevision
) {
}
