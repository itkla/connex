package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for adding an organization administrator by email. {@code acknowledgeConfinement}
 * accepts that the change may leave the account privileged without a passkey approved in the
 * organization (#1534); only {@code true} acknowledges.
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
