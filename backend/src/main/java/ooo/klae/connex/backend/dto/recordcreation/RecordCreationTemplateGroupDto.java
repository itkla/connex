package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RecordCreationTemplateGroupDto(
    @NotBlank String key,
    @NotNull @Valid LocalizedTextDto label,
    @Valid LocalizedTextDto description,
    @NotNull List<@Valid RecordCreationTemplateFieldDto> fields
) {
}
