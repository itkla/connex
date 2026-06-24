package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for changing a workspace member's role.
 */
@Data
public class UpdateMemberRoleRequest {
    @NotBlank
    private String role;
}
