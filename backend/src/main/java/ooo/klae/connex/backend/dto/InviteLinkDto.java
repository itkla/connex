package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Owner-facing view of a shareable invite link, including the token so the owner can copy the link.
 */
@Data
@NoArgsConstructor
public class InviteLinkDto {
    private int id;
    private String token;
    private String role;
    private String expiresAt;
    private Integer maxUses;
    private int usedCount;
    private boolean revoked;
    private String createdByLabel;
    private String createdAt;
}
