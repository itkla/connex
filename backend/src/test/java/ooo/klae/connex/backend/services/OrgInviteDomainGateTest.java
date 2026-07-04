package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * The organization allowlist is the ceiling on every workspace invite/join path (#316, Option B):
 * link redemption, email-token create/accept, and admin-adds-existing are all constrained to the
 * org's approved domains, on top of any per-workspace list. An org with no allowlist is
 * unrestricted, so workspaces keep operating as-is.
 */
class OrgInviteDomainGateTest extends AbstractServiceTest {

    @Autowired private InviteService inviteService;
    @Autowired private InviteLinkService inviteLinkService;
    @Autowired private OrgAllowedDomainService orgAllowedDomainService;
    @Autowired private WorkspaceService workspaceService;

    private int workspaceId;
    private int orgId;

    @BeforeEach
    void freshOrgOwnedByCurrentUser() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Org Gate WS " + unique(), currentUser.getId());
        workspaceId = ws.getId();
        orgId = workspaceService.getOrgId(workspaceId);
    }

    private User register(String email) {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(email);
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    @Test
    void createInvite_isConstrainedByTheOrgAllowlist() {
        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "acme.com");

        assertThrows(ForbiddenException.class,
            () -> inviteService.createInvite(workspaceId, currentUser, "bob@other.com", "member"));
        InviteResultDto ok = inviteService.createInvite(workspaceId, currentUser, "bob@acme.com", "member");
        assertTrue(ok.getInvite() != null);
    }

    @Test
    void createInvite_isUnrestrictedWhenTheOrgHasNoAllowlist() {
        InviteResultDto ok = inviteService.createInvite(workspaceId, currentUser, "bob@anywhere.com", "member");
        assertTrue(ok.getInvite() != null, "an org with no allowlist must not gate invites");
    }

    @Test
    void addExistingMember_isConstrainedByTheOrgAllowlist() {
        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "acme.com");
        User outsider = register("outsider-" + unique() + "@other.com");
        User insider = register("insider-" + unique() + "@acme.com");

        assertThrows(ForbiddenException.class,
            () -> inviteService.addExistingMember(workspaceId, currentUser.getId(), outsider.getEmail(), "member"));
        inviteService.addExistingMember(workspaceId, currentUser.getId(), insider.getEmail(), "member");
    }

    @Test
    void acceptInvite_appliesTheOrgCeilingAtAcceptTime() {
        String email = "newcomer-" + unique() + "@acme.com";
        InviteResultDto invite = inviteService.createInvite(workspaceId, currentUser, email, "member");
        User invitee = register(email);

        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "different.com");

        assertThrows(ForbiddenException.class,
            () -> inviteService.acceptInvite(invite.getInvite().getToken(), invitee),
            "a policy tightened after the invite must block acceptance");
    }

    @Test
    void redeemLink_isBlockedByTheOrgGateEvenWithNoWorkspaceList() {
        orgAllowedDomainService.addDomain(orgId, currentUser.getId(), "acme.com");
        InviteLinkDto link = inviteLinkService.createLink(workspaceId, currentUser, "member", null, null);

        assertThrows(ForbiddenException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), register("out-" + unique() + "@other.com")));
        User insider = register("in-" + unique() + "@acme.com");
        inviteLinkService.redeemLink(link.getToken(), insider);
        assertTrue(workspaceMapper.isMember(workspaceId, insider.getId()));
    }

    @Test
    void redeemLink_isUnrestrictedWhenBothListsEmpty() {
        InviteLinkDto link = inviteLinkService.createLink(workspaceId, currentUser, "member", null, null);
        User user = register("anyone-" + unique() + "@anywhere.com");

        inviteLinkService.redeemLink(link.getToken(), user);
        assertTrue(workspaceMapper.isMember(workspaceId, user.getId()));
    }
}
