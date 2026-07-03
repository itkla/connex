package ooo.klae.connex.backend.dto;

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

    // Outcome to record: TRUE = won, FALSE = lost. Null defaults to lost server-side.
    private Boolean won;

    @Size(max = 1000)
    private String reason;

    private Double actualValue;
}