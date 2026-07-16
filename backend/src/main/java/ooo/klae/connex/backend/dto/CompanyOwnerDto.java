package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to assign or clear a company's owner. */
@Data
@NoArgsConstructor
public class CompanyOwnerDto {
    private Integer ownerId;
}
