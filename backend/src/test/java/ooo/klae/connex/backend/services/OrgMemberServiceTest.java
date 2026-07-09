package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/**
 * Org-level authorization and membership management (#316): admin/owner gates,
 * owner-only member management, the last-owner guard, and that a freshly minted
 * organization records its creator as the founding owner (the runtime equivalent
 * of the V44 backfill).
 */
class OrgMemberServiceTest extends AbstractServiceTest {

    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceService workspaceService;

    private int newOrgOwnedBy(int userId) {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        orgMemberMapper.addMember(org.getId(), userId, "owner");
        return org.getId();
    }

    @Test
    void requireOrgAdmin_acceptsOwnerAndAdmin_rejectsNonMember() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");
        User outsider = newUser();

        orgMemberService.requireOrgAdmin(orgId, currentUser.getId());
        orgMemberService.requireOrgAdmin(orgId, admin.getId());
        assertThrows(ForbiddenException.class, () -> orgMemberService.requireOrgAdmin(orgId, outsider.getId()));
    }

    @Test
    void requireOrgOwner_rejectsAdmin() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        orgMemberService.requireOrgOwner(orgId, currentUser.getId());
        assertThrows(ForbiddenException.class, () -> orgMemberService.requireOrgOwner(orgId, admin.getId()));
    }

    @Test
    void setMember_ownerAddsAdmin_nonOwnerRefused() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User target = newUser();

        orgMemberService.setMember(orgId, currentUser.getId(), target.getId(), "admin");
        assertEquals("admin", orgMemberMapper.getRole(orgId, target.getId()));

        User anotherAdmin = newUser();
        orgMemberMapper.addMember(orgId, anotherAdmin.getId(), "admin");
        assertThrows(ForbiddenException.class,
            () -> orgMemberService.setMember(orgId, anotherAdmin.getId(), newUser().getId(), "admin"));
    }

    @Test
    void setMember_rejectsUnknownRoleAndMissingUser() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        assertThrows(BadRequestException.class,
            () -> orgMemberService.setMember(orgId, currentUser.getId(), newUser().getId(), "superuser"));
        assertThrows(ResourceNotFoundException.class,
            () -> orgMemberService.setMember(orgId, currentUser.getId(), 999999, "admin"));
    }

    @Test
    void setMemberByEmail_ownerAddsByEmail_caseInsensitive_unknownRejected() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User target = newUser();

        orgMemberService.setMemberByEmail(orgId, currentUser.getId(), target.getEmail().toUpperCase(), "admin");
        assertEquals("admin", orgMemberMapper.getRole(orgId, target.getId()),
            "an existing account is matched by email case-insensitively and granted the role");

        assertThrows(BadRequestException.class,
            () -> orgMemberService.setMemberByEmail(orgId, currentUser.getId(), "nobody-" + unique() + "@example.com", "admin"));
    }

    @Test
    void setMemberByEmail_enforcesOwnerGateAndLastOwnerGuard() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        assertThrows(ForbiddenException.class,
            () -> orgMemberService.setMemberByEmail(orgId, admin.getId(), newUser().getEmail(), "admin"));
        assertThrows(BadRequestException.class,
            () -> orgMemberService.setMemberByEmail(orgId, currentUser.getId(), currentUser.getEmail(), "admin"));
        assertEquals("owner", orgMemberMapper.getRole(orgId, currentUser.getId()));
    }

    @Test
    void soleOwnerCannotBeDemotedOrRemoved() {
        int orgId = newOrgOwnedBy(currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> orgMemberService.setMember(orgId, currentUser.getId(), currentUser.getId(), "admin"));
        assertThrows(BadRequestException.class,
            () -> orgMemberService.removeMember(orgId, currentUser.getId(), currentUser.getId()));
        assertEquals("owner", orgMemberMapper.getRole(orgId, currentUser.getId()));
    }

    @Test
    void removeMember_dropsAdmin_andReportsMissing() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        orgMemberService.removeMember(orgId, currentUser.getId(), admin.getId());
        assertTrue(orgMemberMapper.getRole(orgId, admin.getId()) == null);
        assertThrows(ResourceNotFoundException.class,
            () -> orgMemberService.removeMember(orgId, currentUser.getId(), admin.getId()));
    }

    @Test
    void listMembers_requiresOrgAdmin() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        assertEquals(1, orgMemberService.listMembers(orgId, currentUser.getId()).size());
        assertThrows(ForbiddenException.class, () -> orgMemberService.listMembers(orgId, newUser().getId()));
    }

    @Test
    void creatingAWorkspaceMakesTheCreatorTheFoundingOrgOwner() {
        clearRequestContext();
        WorkspaceMembershipDto created = workspaceService.createWorkspace("Fresh " + unique(), currentUser.getId());
        int orgId = workspaceService.getOrgId(created.getId());
        assertEquals("owner", orgMemberMapper.getRole(orgId, currentUser.getId()),
            "an unresolved-context creator mints a fresh org and becomes its owner");
        assertEquals(orgId, created.getOrgId(),
            "the returned membership carries the minted org id so the client can resolve the org area");
        assertEquals("owner", created.getOrgRole(),
            "the founding creator's org role is populated on the create response, not left null");
        assertTrue(created.getOrgName() != null && !created.getOrgName().isBlank(),
            "the org name is populated on the create response");
    }
}
