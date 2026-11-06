package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Only supplied flags change; omitted or null flags preserve the locked current restriction. */
@Data
@NoArgsConstructor
public class PersonRestrictionsDto {
    private Boolean suspended;

    private Boolean provisionCeased;
}
