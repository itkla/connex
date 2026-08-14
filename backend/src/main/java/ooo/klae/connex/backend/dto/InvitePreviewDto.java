package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What an invitee sees before accepting: which workspace, who invited them, and
 * whether the token is still redeemable. No token is echoed back.
 */
@Data
@NoArgsConstructor
public class InvitePreviewDto {
    private String flowId;
    private int workspaceId;
    private String workspaceName;
    private String email;
    private String role;
    private String invitedByLabel;
    private String status;
    private boolean valid;
}
