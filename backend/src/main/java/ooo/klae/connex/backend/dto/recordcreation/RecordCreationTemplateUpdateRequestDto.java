package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record RecordCreationTemplateUpdateRequestDto(
    @NotNull @Valid LocalizedTextDto name,
    @Valid LocalizedTextDto description,
    @NotNull @Valid RecordCreationTemplateDefinitionDto definition,
    boolean enabled,
    @PositiveOrZero int expectedTemplateRevision,
    @Positive int expectedTemplateVersion,
    @PositiveOrZero int expectedSetRevision,
    boolean confirmImpact
) {
}
