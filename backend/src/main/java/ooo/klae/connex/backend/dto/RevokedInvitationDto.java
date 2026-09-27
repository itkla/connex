package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A pending workspace invitation that a verified email change revoked. The grant was addressed to
 * the account's previous address, so it does not survive the move; returning it makes an otherwise
 * silent loss visible to the account holder, who can ask the workspace to re-invite the new address.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevokedInvitationDto {
    private int workspaceId;
    private Integer orgId;
    private String workspaceName;
}
