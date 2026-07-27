package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Validated filters and group-level pagination for the identity collision report.
 */
@Data
@NoArgsConstructor
public class IdentityCollisionQuery {

    @Pattern(regexp = "person|company")
    private String recordType;

    @Pattern(regexp = "email|phone|domain|external_id")
    private String kind;

    @Min(1)
    @Max(1_000_000)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int size = 50;
}
