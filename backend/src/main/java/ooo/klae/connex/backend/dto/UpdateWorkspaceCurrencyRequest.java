package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.validation.ValidCurrency;

/** Explicit workspace default with a precondition against concurrent settings changes. */
@Data
@NoArgsConstructor
public class UpdateWorkspaceCurrencyRequest {
    @NotBlank
    @ValidCurrency
    private String defaultCurrency;

    @NotNull
    @PositiveOrZero
    private Long expectedIdentityVersion;
}
