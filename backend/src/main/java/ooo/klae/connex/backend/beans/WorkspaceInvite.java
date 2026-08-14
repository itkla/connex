package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A pending or resolved invitation for an email address to join a workspace.
 */
@Data
@NoArgsConstructor
public class WorkspaceInvite {
    private int id;
    private int workspaceId;
    private String email;
    private String role;
    private String token;
    private String tokenHash;
    private String status;
    private Integer invitedById;
    private Integer acceptedById;
    private String expiresAt;
    private String acceptedAt;
    private String createdAt;
    private String updatedAt;
}
