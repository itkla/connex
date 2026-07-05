package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for adding an organization administrator by email.
 */
@Data
public class AddOrgMemberRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String orgRole;
}
