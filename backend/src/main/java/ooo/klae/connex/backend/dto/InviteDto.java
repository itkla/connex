package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A pending workspace invite as shown to workspace admins. Carries the token so
 * the admin can re-copy the acceptance link.
 */
@Data
@NoArgsConstructor
public class InviteDto {
    private int id;
    private String email;
    private String role;
    private String status;
    private String token;
    private String invitedByLabel;
    private String expiresAt;
    private String createdAt;
}
