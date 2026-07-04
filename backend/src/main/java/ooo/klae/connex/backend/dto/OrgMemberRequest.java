package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * Request body for setting an organization member's role.
 */
@Data
public class OrgMemberRequest {
    @NotBlank
    private String orgRole;
}
