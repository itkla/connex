package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** The accepting account's acknowledgement of possible privileged-MFA confinement. */
@Data
@NoArgsConstructor
public class AcceptOwnershipOfferRequest {
    private Boolean acknowledgeConfinement;
}
