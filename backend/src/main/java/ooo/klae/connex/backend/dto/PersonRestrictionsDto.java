package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Requested processing and third-party-provision restrictions for a contact. */
@Data
@NoArgsConstructor
public class PersonRestrictionsDto {
    @NotNull
    private Boolean suspended;

    @NotNull
    private Boolean provisionCeased;
}
