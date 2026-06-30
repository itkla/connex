package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invitee-facing preview of an invite link shown on the accept screen. The token is the secret
 * and is never echoed back; {@code valid} is false once the link is revoked, expired, or exhausted.
 */
@Data
@NoArgsConstructor
public class InviteLinkPreviewDto {
    private int workspaceId;
    private String workspaceName;
    private String role;
    private boolean valid;
}
