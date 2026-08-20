package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.InviteLinkDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Shareable invite links: creation/revocation are MEMBER_MANAGE-gated; redemption joins the
 * authenticated user with the link's role, honours max-uses, and is idempotent per user (#81 Phase 3).
 */
class InviteLinkServiceTest extends AbstractServiceTest {

    @Autowired private InviteLinkService inviteLinkService;
    @Autowired private RoleService roleService;
    @Autowired private WorkspaceService workspaceService;

    /** A user who is not a member of the default workspace. */
    private User outsider() {
        String s = unique();
        User u = new User();
        u.setUsername("out_" + s);
        u.setDisplayName("Out " + s);
        u.setEmail(s + "@example.com");
        u.setPasswordHash("hash_" + s);
        u.setTimezone("UTC");
        userMapper.insert(u);
        return u;
    }

    @Test
    void createLink_asOwner_returnsLink() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);

        assertNotNull(link.getToken());
        assertEquals("member", link.getRole());
        assertEquals(0, link.getUsedCount());
        assertFalse(link.isRevoked());
    }

    @Test
    void createLink_requiresMemberManage() {
        User member = newUser(); // plain member, lacks MEMBER_MANAGE

        assertThrows(ForbiddenException.class,
            () -> inviteLinkService.createLink(workspace.getId(), member, "member", null, null));
    }

    @Test
    void memberManageDelegateCanCreateMemberLinkButNotAdminLink() {
        User delegate = newUser();
        WorkspaceRole memberRole = workspaceService.builtInRoles().stream()
            .filter(role -> "member".equals(role.getName()))
            .findFirst()
            .orElseThrow();
        List<String> permissions = new ArrayList<>(memberRole.getPermissions());
        permissions.add(Permission.MEMBER_MANAGE.name());
        WorkspaceRole manager = roleService.createRole(
            workspace.getId(), currentUser.getId(), "Link Inviter", permissions);
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), delegate.getId(), manager.getId());
        authenticateAs(delegate, workspace.getId());

        InviteLinkDto memberLink = inviteLinkService.createLink(
            workspace.getId(), delegate, "member", null, null);
        assertNotNull(memberLink.getToken());
        assertThrows(
            ForbiddenException.class,
            () -> inviteLinkService.createLink(
                workspace.getId(), delegate, "admin", null, null));
    }

    @Test
    void redeemLink_joinsUserWithLinkRole() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "admin", null, null);
        User user = outsider();

        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
        assertEquals("admin", workspaceMapper.getRole(workspace.getId(), user.getId()));
    }

    @Test
    void redeemLink_revalidatesCreatorsCurrentGrantAuthority() {
        InviteLinkDto link = inviteLinkService.createLink(
            workspace.getId(), currentUser, "member", null, null);
        WorkspaceRole manager = roleService.createRole(
            workspace.getId(),
            currentUser.getId(),
            "Restricted link creator",
            List.of(Permission.MEMBER_MANAGE.name()));
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), currentUser.getId(), manager.getId());
        User user = outsider();

        assertThrows(
            ForbiddenException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), user));
        assertFalse(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }

    @Test
    void redeemLink_revoked_rejected() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        inviteLinkService.revokeLink(workspace.getId(), link.getId(), currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), outsider()));
    }

    @Test
    void redeemLink_honorsMaxUses() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, 1);
        inviteLinkService.redeemLink(link.getToken(), outsider());

        assertThrows(BadRequestException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), outsider()));
    }

    @Test
    void redeemLink_sameUserTwice_isIdempotent() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, 1);
        User user = outsider();

        inviteLinkService.redeemLink(link.getToken(), user);
        inviteLinkService.redeemLink(link.getToken(), user);

        assertTrue(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }

    @Test
    void redeemLink_afterRemoval_doesNotBypassMaxUses() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, 1);
        User user = outsider();
        inviteLinkService.redeemLink(link.getToken(), user);
        workspaceMapper.removeMember(workspace.getId(), user.getId());

        assertThrows(BadRequestException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), user));
        assertFalse(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }

    @Test
    void redeemLink_afterRemoval_revokedLink_rejected() {
        InviteLinkDto link = inviteLinkService.createLink(workspace.getId(), currentUser, "member", null, null);
        User user = outsider();
        inviteLinkService.redeemLink(link.getToken(), user);
        workspaceMapper.removeMember(workspace.getId(), user.getId());
        inviteLinkService.revokeLink(workspace.getId(), link.getId(), currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> inviteLinkService.redeemLink(link.getToken(), user));
        assertFalse(workspaceMapper.isMember(workspace.getId(), user.getId()));
    }
}
