package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * The domain allowlist gates invite-LINK redemption (the broad self-serve channel): a user whose
 * email domain isn't allowlisted cannot redeem, an allowlisted domain can, and an empty allowlist
 * lets anyone in. (Explicit email invites are deliberately not gated — see InviteServiceTest.) (#81 Phase 4)
 */
class InviteLinkDomainGateTest extends AbstractServiceTest {

    @Autowired private InviteLinkService inviteLinkService;
    @Autowired private AllowedDomainService allowedDomainService;

    private User outsiderWithDomain(String domain) {
        String s = unique();
        User u = new User();
        u.setUsername("out_" + s);
        u.setDisplayName("Out " + s);
        u.setEmail(s + "@" + domain);
        u.setPasswordHash("hash_" + s);
        u.setTimezone("UTC");
        userMapper.insert(u);
        return u;
    }

    @Test
    void redeem_blockedWhenDomainNotAllowlisted() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "acme.com");
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);

        assertThrows(ForbiddenException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), outsiderWithDomain("other.com")));
    }

    @Test
    void redeem_allowedWhenDomainOnAllowlist() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "acme.com");
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        User user = outsiderWithDomain("acme.com");

        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }

    @Test
    void redeem_allowedWhenNoAllowlist() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        User user = outsiderWithDomain("anywhere.com");

        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }
}
