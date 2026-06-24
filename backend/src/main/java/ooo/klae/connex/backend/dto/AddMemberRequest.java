package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Request body for adding an existing Connex user to a workspace by email.
 */
@Data
public class AddMemberRequest {
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    /** {@code member} or {@code admin}; defaults to {@code member} when blank. */
    private String role;
}
