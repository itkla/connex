package ooo.klae.connex.backend.dto;

import lombok.Data;

/**
 * Request body for changing a workspace member's role. Supply either a built-in
 * {@code role} (owner/admin/member) or a custom {@code roleId}. {@code acknowledgeConfinement}
 * accepts that the change may leave the member privileged without a passkey approved in the
 * organization (#1534); only {@code true} acknowledges. A new owner promotion creates an offer
 * preserving the current membership; confinement acknowledgement belongs to its offeree at
 * acceptance instead.
 */
@Data
public class UpdateMemberRoleRequest {
    private String role;
    private Integer roleId;
    private Boolean acknowledgeConfinement;
}
