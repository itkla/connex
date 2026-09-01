package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RecordCreationTemplateDefinitionDto(
    int schemaVersion,
    @NotNull List<@Valid RecordCreationTemplateGroupDto> groups
) {
}
