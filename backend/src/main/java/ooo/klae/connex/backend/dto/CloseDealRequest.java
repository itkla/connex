package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /api/deals/{id}/close.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloseDealRequest {

    private Boolean won;

    @Size(max = 1000)
    private String reason;

    @DecimalMin("0.00")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal actualValue;
}
