package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.constraints.PositiveOrZero;

public record RecordCreationTemplateStateRequestDto(
    @PositiveOrZero int expectedTemplateRevision,
    @PositiveOrZero int expectedSetRevision,
    boolean confirmImpact
) {
}
