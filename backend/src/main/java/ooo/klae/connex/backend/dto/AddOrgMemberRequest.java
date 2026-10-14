package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for addressing an organization membership by email. A new owner promotion creates
 * an offer and preserves any existing membership. {@code acknowledgeConfinement}
 * accepts that the change may leave the account privileged without a passkey approved in the
 * organization (#1534); only {@code true} acknowledges. Ownership consent acknowledges confinement
 * at acceptance, rather than offer creation.
 */
@Data
public class AddOrgMemberRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String orgRole;
    private Boolean acknowledgeConfinement;
}
