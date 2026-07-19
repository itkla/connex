package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to change only a deal's manually projected value. */
@Data
@NoArgsConstructor
public class DealValueUpdateRequest {
    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal value;
}
