package ooo.klae.connex.backend.dto.recordcreation;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

public record GuidedPersonCreateRequestDto(
    @NotNull @Valid GuidedPersonRecordDto record,
    @NotNull @Valid RecordCreationTemplateUseDto templateUse,
    @NotNull @Size(max = 40) Map<@NotNull @Positive Integer, @NotNull JsonNode> customFields,
    @NotNull @Size(max = 20) List<@NotNull @Positive Integer> tagIds
) {
}
