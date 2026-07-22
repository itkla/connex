package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInvite;

/** Verifies the single-use, expiry-aware workspace invite acceptance claim. */
class InviteMapperTest extends AbstractMapperTest {

    @Autowired private InviteMapper inviteMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void claimAcceptanceFirstClaimPersistsAcceptance() {
        WorkspaceInvite invite = pendingInvite();
        User recipient = newUser();

        assertEquals(1, claim(invite, recipient));

        WorkspaceInvite accepted = inviteMapper.findByToken(invite.getToken());
        assertEquals("accepted", accepted.getStatus());
        assertEquals(recipient.getId(), accepted.getAcceptedById());
        assertNotNull(accepted.getAcceptedAt());
    }

    @Test
    void claimAcceptanceRepeatedClaimDoesNotOverwriteFirstRecipient() {
        WorkspaceInvite invite = pendingInvite();
        User firstRecipient = newUser();
        User secondRecipient = newUser();

        assertEquals(1, claim(invite, firstRecipient));
        assertEquals(0, claim(invite, secondRecipient));

        WorkspaceInvite accepted = inviteMapper.findByToken(invite.getToken());
        assertEquals("accepted", accepted.getStatus());
        assertEquals(firstRecipient.getId(), accepted.getAcceptedById());
        assertNotNull(accepted.getAcceptedAt());
    }

    @Test
    void claimAcceptanceRevokedInviteReturnsZero() {
        WorkspaceInvite invite = pendingInvite();
        User recipient = newUser();

        assertEquals(1, inviteMapper.markRevoked(invite.getId(), workspace.getId()));
        assertEquals(0, claim(invite, recipient));

        WorkspaceInvite revoked = inviteMapper.findByToken(invite.getToken());
        assertEquals("revoked", revoked.getStatus());
        assertNull(revoked.getAcceptedById());
        assertNull(revoked.getAcceptedAt());
    }

    @Test
    void claimAcceptanceExpiredInviteReturnsZero() {
        WorkspaceInvite invite = pendingInvite();
        User recipient = newUser();
        assertEquals(1, jdbcTemplate.update(
            "UPDATE workspace_invite SET expires_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY) WHERE id = ?",
            invite.getId()));

        assertEquals(0, claim(invite, recipient));

        WorkspaceInvite expired = inviteMapper.findByToken(invite.getToken());
        assertEquals("pending", expired.getStatus());
        assertNull(expired.getAcceptedById());
        assertNull(expired.getAcceptedAt());
    }

    private WorkspaceInvite pendingInvite() {
        User inviter = newUser();
        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setWorkspaceId(workspace.getId());
        invite.setEmail("recipient-" + unique() + "@example.com");
        invite.setRole("member");
        invite.setToken("invite-" + unique());
        invite.setInvitedById(inviter.getId());
        inviteMapper.insert(invite);
        return invite;
    }

    private int claim(WorkspaceInvite invite, User recipient) {
        return inviteMapper.claimAcceptance(
            invite.getId(), invite.getToken(), invite.getWorkspaceId(), recipient.getId());
    }
}
