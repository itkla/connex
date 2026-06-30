package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A shareable, owner-issued link token for joining a workspace. Not bound to an email;
 * redeemable up to {@code maxUses} times (null = unlimited) until {@code expiresAt}
 * (null = never) or revocation. SQL lives in {@code resources/mappers/InviteLinkMapper.xml}.
 */
@Data
@NoArgsConstructor
public class WorkspaceInviteLink {
    private int id;
    private int workspaceId;
    private String token;
    private String role;
    private String expiresAt;
    private Integer maxUses;
    private int usedCount;
    private boolean revoked;
    private Integer createdById;
    private String createdAt;
    private String updatedAt;
}
