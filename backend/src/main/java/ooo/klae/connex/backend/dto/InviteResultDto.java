package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The outcome of inviting someone to a workspace by email. Exactly one field is
 * populated: {@code invite} for an emailed token invite (the address has no
 * Connex account yet), or {@code member} when the address belongs to an existing
 * Connex user, who is added as a pending member and notified in-app instead.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InviteResultDto {
    private InviteDto invite;
    private MemberDto member;
}
