package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * With registration verification enabled, redeeming a shareable invite link into a
 * DOMAIN-RESTRICTED workspace requires a verified email — closing the bypass where an
 * unverified account registers with an allowed-domain address it does not own (#282).
 * Workspaces with no domain allowlist are unaffected.
 */
@TestPropertySource(properties = "connex.registration-verification.enabled=true")
class RegistrationVerificationGateTest extends AbstractServiceTest {

    @Autowired private InviteLinkService inviteLinkService;
    @Autowired private AllowedDomainService allowedDomainService;

    private User outsider(String domain) {
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
    void domainRestricted_unverifiedEmail_blocked() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "acme.com");
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);

        assertThrows(ForbiddenException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), outsider("acme.com")),
            "an allowed-domain but unverified email must not satisfy the domain gate");
    }

    @Test
    void domainRestricted_verifiedEmail_allowed() {
        allowedDomainService.addDomain(workspace.getId(), currentUser.getId(), "acme.com");
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        User user = outsider("acme.com");
        userMapper.markEmailVerified(user.getId());

        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }

    @Test
    void noAllowlist_unverifiedEmail_stillAllowed() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        User user = outsider("anywhere.com");

        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }
}
