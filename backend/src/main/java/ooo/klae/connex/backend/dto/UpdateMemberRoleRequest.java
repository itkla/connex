package ooo.klae.connex.backend.dto;

import lombok.Data;

/**
 * Request body for changing a workspace member's role. Supply either a built-in
 * {@code role} (owner/admin/member) or a custom {@code roleId}.
 */
@Data
public class UpdateMemberRoleRequest {
    private String role;
    private Integer roleId;
}
