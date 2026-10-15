package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
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
@org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
class OrgMemberServiceTest extends AbstractServiceTest {

    @Autowired private OrgMemberService orgMemberService;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OwnershipOfferService ownershipOfferService;

    private static final String NOT_A_TENANT =
        "Only active members of a workspace in this organization can be added to it";

    private int newOrgOwnedBy(int userId) {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        orgMemberMapper.addMember(org.getId(), userId, "owner");
        return org.getId();
    }

    private Workspace newWorkspaceIn(int orgId) {
        Workspace tenantWorkspace = new Workspace();
        tenantWorkspace.setOrgId(orgId);
        tenantWorkspace.setName("Tenant " + unique());
        tenantWorkspace.setSlug("tenant-" + unique());
        workspaceMapper.insert(tenantWorkspace);
        return tenantWorkspace;
    }

    private User newTenantOf(int orgId) {
        User tenant = newUser();
        workspaceMapper.addMember(newWorkspaceIn(orgId).getId(), tenant.getId(), "member");
        return tenant;
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
        User target = newTenantOf(orgId);

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
        BadRequestException unknown = assertThrows(BadRequestException.class,
            () -> orgMemberService.setMember(orgId, currentUser.getId(), 999999, "admin"));
        assertEquals(NOT_A_TENANT, unknown.getMessage());
    }

    @Test
    void setMemberByEmail_ownerAddsByEmail_caseInsensitive_unknownRejected() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User target = newTenantOf(orgId);

        orgMemberService.setMemberByEmail(orgId, currentUser.getId(), target.getEmail().toUpperCase(), "admin");
        assertEquals("admin", orgMemberMapper.getRole(orgId, target.getId()),
            "an existing account is matched by email case-insensitively and granted the role");

        BadRequestException unknown = assertThrows(BadRequestException.class,
            () -> orgMemberService.setMemberByEmail(orgId, currentUser.getId(), "nobody-" + unique() + "@example.com", "admin"));
        assertEquals(NOT_A_TENANT, unknown.getMessage());
    }

    /**
     * A new organization membership goes only to an existing tenant (#1534). Every account that
     * is not one, and every unknown account, gets the same refusal, so the answer reveals nothing
     * about whether an account exists.
     */
    @Test
    void aNewMembershipIsGrantedOnlyToAnActiveTenantAndEveryRefusalReadsTheSame() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        Workspace tenantWorkspace = newWorkspaceIn(orgId);
        User outsider = newUser();
        User pending = newUser();
        workspaceMapper.addPendingMember(tenantWorkspace.getId(), pending.getId(), "member");
        User tornDownTenant = newUser();
        Workspace tornDown = newWorkspaceIn(orgId);
        workspaceMapper.addMember(tornDown.getId(), tornDownTenant.getId(), "member");
        jdbcTemplate.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", tornDown.getId());

        for (User refused : List.of(outsider, pending, tornDownTenant)) {
            BadRequestException byId = assertThrows(BadRequestException.class,
                () -> orgMemberService.setMember(orgId, currentUser.getId(), refused.getId(), "admin"));
            BadRequestException byEmail = assertThrows(BadRequestException.class,
                () -> orgMemberService.setMemberByEmail(orgId, currentUser.getId(), refused.getEmail(), "admin"));
            assertEquals(NOT_A_TENANT, byId.getMessage());
            assertEquals(NOT_A_TENANT, byEmail.getMessage());
            assertNull(orgMemberMapper.getRole(orgId, refused.getId()));
        }

        String unknownEmail = "nobody-" + unique() + "@example.com";
        assertEquals(
            assertThrows(BadRequestException.class, () -> orgMemberService.setMemberByEmail(
                orgId, currentUser.getId(), unknownEmail, "bogus")).getMessage(),
            assertThrows(BadRequestException.class, () -> orgMemberService.setMemberByEmail(
                orgId, currentUser.getId(), outsider.getEmail(), "bogus")).getMessage());

        User tenant = newUser();
        workspaceMapper.addMember(tenantWorkspace.getId(), tenant.getId(), "member");
        orgMemberService.setMember(orgId, currentUser.getId(), tenant.getId(), "admin");
        assertEquals("admin", orgMemberMapper.getRole(orgId, tenant.getId()));
    }

    /** Re-roling an existing org member does not require tenancy (#1534). */
    @Test
    void anExistingOrgMemberIsReRoledWithoutTenancy() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        ooo.klae.connex.backend.support.OwnershipOfferTestFixtures.stepUpCurrent(jdbcTemplate, currentUser);
        var offer = orgMemberService.setMember(orgId, currentUser.getId(), admin.getId(), "owner");
        assertEquals("admin", orgMemberMapper.getRole(orgId, admin.getId()));
        authenticateAs(admin, workspace.getId());
        ooo.klae.connex.backend.support.OwnershipOfferTestFixtures.stepUpCurrent(jdbcTemplate, admin);
        ownershipOfferService.accept(Long.parseLong(offer.offer().id()), admin.getId(), false);
        assertEquals("owner", orgMemberMapper.getRole(orgId, admin.getId()));
    }

    /**
     * Leaving removes only the caller's own membership and is audited (#1534); the last owner
     * cannot leave, and a non-member has nothing to leave.
     */
    @Test
    void aMemberLeavesTheOrganizationButTheLastOwnerCannot() {
        int orgId = newOrgOwnedBy(currentUser.getId());
        User admin = newUser();
        orgMemberMapper.addMember(orgId, admin.getId(), "admin");

        orgMemberService.leave(orgId, admin.getId());

        assertNull(orgMemberMapper.getRole(orgId, admin.getId()));
        assertEquals("owner", orgMemberMapper.getRole(orgId, currentUser.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'org.member.leave' AND org_id = ?", Integer.class, orgId));
        assertThrows(ResourceNotFoundException.class, () -> orgMemberService.leave(orgId, admin.getId()));
        assertThrows(BadRequestException.class, () -> orgMemberService.leave(orgId, currentUser.getId()));
        assertEquals("owner", orgMemberMapper.getRole(orgId, currentUser.getId()));
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
