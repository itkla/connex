package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to assign or clear a contact's owner. */
@Data
@NoArgsConstructor
public class PersonOwnerDto {
    private Integer ownerId;
}
