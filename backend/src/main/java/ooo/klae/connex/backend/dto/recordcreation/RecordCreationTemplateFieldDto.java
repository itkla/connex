package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RecordCreationTemplateFieldDto(
    @NotBlank String key,
    boolean required,
    @Valid LocalizedTextDto helpText,
    @Valid LocalizedTextDto placeholder,
    @Valid RecordCreationDefaultSpecDto defaultSpec
) {
}
