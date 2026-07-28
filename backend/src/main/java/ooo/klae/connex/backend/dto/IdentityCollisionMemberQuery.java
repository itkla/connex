package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Validated identity and keyset cursor for one collision group's member page.
 */
@Data
@NoArgsConstructor
public class IdentityCollisionMemberQuery {

    @NotBlank
    @Pattern(regexp = "person|company")
    private String recordType;

    @NotBlank
    @Pattern(regexp = "email|phone|domain|external_id")
    private String kind;

    @NotBlank
    @Size(max = 512)
    private String normalizedValue;

    @Min(0)
    private int afterRecordId = 0;

    @Min(1)
    @Max(100)
    private int size = 50;
}
