package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An address-only invitation for workspace administrators. The opaque id identifies an
 * invitation, never an account. A raw token is available only at creation. Historical pending
 * membership invitations have no expiry.
 */
@Data
@NoArgsConstructor
public class InviteDto {
    private String id;
    private String email;
    private String role;
    private String status;
    private String token;
    private String invitedByLabel;
    private String expiresAt;
    private String createdAt;
}
