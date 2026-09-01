package ooo.klae.connex.backend.dto.recordcreation;

import jakarta.validation.constraints.NotNull;

public record LocalizedTextDto(
    @NotNull String en,
    @NotNull String ja
) {
}
