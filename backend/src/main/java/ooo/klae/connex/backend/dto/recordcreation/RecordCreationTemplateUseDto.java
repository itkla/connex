package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;

public record RecordCreationTemplateUseDto(
    @NotBlank @Size(max = 64) String templateId,
    @Positive int templateVersion,
    @PositiveOrZero int templateSetRevision,
    @NotNull RecordCreationEntryPoint entryPoint,
    @NotNull @Valid RecordCreationContextDto context
) {
}
