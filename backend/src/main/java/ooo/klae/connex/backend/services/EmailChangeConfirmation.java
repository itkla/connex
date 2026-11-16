package ooo.klae.connex.backend.services;

import java.util.List;

import ooo.klae.connex.backend.dto.RevokedInvitationDto;

/**
 * The outcome of a committed email change: the account it applied to and the pending invitations
 * it revoked, which {@link RevokedGrantNotificationCleanup} still has to clear from each revoked
 * workspace's tenant catalog (#1708).
 *
 * @param userId the account whose address changed
 * @param revokedInvitations the revoked pending invitations, in ascending workspace id
 */
public record EmailChangeConfirmation(int userId, List<RevokedInvitationDto> revokedInvitations) {

    /** Copies the revoked invitations so the record stays immutable. */
    public EmailChangeConfirmation {
        revokedInvitations = List.copyOf(revokedInvitations);
    }
}
