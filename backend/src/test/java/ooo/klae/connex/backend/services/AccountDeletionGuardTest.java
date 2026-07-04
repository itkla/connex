package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/**
 * Deleting an account must not orphan a workspace or organization: because both membership tables
 * are {@code ON DELETE CASCADE}, a self-delete would rip out the owner row and bypass the last-owner
 * guards that live on the member operations. The account path re-applies those guards (#316).
 */
class AccountDeletionGuardTest extends AbstractServiceTest {

    @Autowired private UserService userService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;

    private int newOrgOwnedBy(int userId) {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        orgMemberMapper.addMember(org.getId(), userId, "owner");
        return org.getId();
    }

    @Test
    void deletingSoleWorkspaceOwner_isRefused() {
        assertThrows(BadRequestException.class, () -> userService.delete(currentUser.getId()),
            "the sole owner of a workspace must transfer ownership before deleting their account");
    }

    @Test
    void workspaceGuard_firesOnlyForTheSoleOwner() {
        assertThrows(BadRequestException.class,
            () -> workspaceService.assertNotSoleOwnerOfAnyWorkspace(currentUser.getId()));
        User plainMember = newUser();
        workspaceService.assertNotSoleOwnerOfAnyWorkspace(plainMember.getId());
    }

    @Test
    void orgGuard_firesForSoleOwner_clearsWhenAnotherOwnerExists() {
        User orgOwner = newUser();
        int orgId = newOrgOwnedBy(orgOwner.getId());

        assertThrows(BadRequestException.class,
            () -> orgMemberService.assertNotSoleOwnerOfAnyOrg(orgOwner.getId()));

        orgMemberMapper.addMember(orgId, newUser().getId(), "owner");
        orgMemberService.assertNotSoleOwnerOfAnyOrg(orgOwner.getId());
    }
}
