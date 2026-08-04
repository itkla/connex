package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.OrganizationIdentityDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutDto;
import ooo.klae.connex.backend.dto.OrganizationLayoutWorkspaceDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;

/** Database-backed organization rename, audit, and restricted-roster contract. */
class OrganizationServiceIntegrationTest extends AbstractServiceTest {
    @Autowired private OrganizationService organizationService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberMapper orgMemberMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void renameAllowsOrgAdminPreservesSlugAuditsOrgScopeAndRefreshesWorkspaceProjection() {
        Organization organization = newOrganization("Original Org");
        orgMemberMapper.addMember(organization.getId(), currentUser.getId(), "admin");
        Workspace child = newWorkspace(organization.getId(), "Child Workspace");
        workspaceMapper.addMember(child.getId(), currentUser.getId(), "owner");
        String slug = organization.getSlug();

        OrganizationIdentityDto renamed = organizationService.rename(
            organization.getId(), currentUser.getId(), "  Renamed Org  ", "Original Org", 0L);

        assertEquals("Renamed Org", renamed.name());
        assertEquals(slug, renamed.slug());
        assertEquals(1L, renamed.identityVersion());
        Organization persisted = organizationMapper.getActiveById(organization.getId());
        assertEquals("Renamed Org", persisted.getName());
        assertEquals(slug, persisted.getSlug());
        var childMembership = workspaceMapper.getMembershipsForUser(currentUser.getId()).stream()
            .filter(membership -> membership.getId() == child.getId())
            .findFirst()
            .orElseThrow();
        assertEquals("Renamed Org", childMembership.getOrgName());
        assertEquals(1L, childMembership.getOrgIdentityVersion());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'org.rename' AND entity_id = ? "
                + "AND workspace_id IS NULL AND org_id = ?",
            Integer.class,
            organization.getId(),
            organization.getId()));
        String changes = jdbcTemplate.queryForObject(
            "SELECT changes FROM audit_log WHERE action = 'org.rename' AND entity_id = ? "
                + "ORDER BY id DESC LIMIT 1",
            String.class,
            organization.getId());
        assertTrue(changes.contains("Original Org"));
        assertTrue(changes.contains("Renamed Org"));
    }

    @Test
    void renameRejectsInvalidNameAndAnotherOrganizationsActor() {
        Organization organization = newOrganization("Protected Org");
        User otherOwner = newUser();
        orgMemberMapper.addMember(organization.getId(), otherOwner.getId(), "owner");

        assertThrows(ForbiddenException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), "Probe", "Protected Org", 0L));
        assertThrows(ForbiddenException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), " ", "Protected Org", 0L));
        assertEquals("Protected Org", organizationMapper.getActiveById(organization.getId()).getName());

        orgMemberMapper.addMember(organization.getId(), currentUser.getId(), "admin");
        assertThrows(BadRequestException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), null, "Protected Org", 0L));
        assertThrows(BadRequestException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), " ", "Protected Org", 0L));
        assertThrows(BadRequestException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), "x".repeat(129), "Protected Org", 0L));
    }

    @Test
    void renameRejectsAStaleEditorWithoutOverwritingTheCommittedName() {
        Organization organization = newOrganization("Original Org");
        orgMemberMapper.addMember(organization.getId(), currentUser.getId(), "admin");

        organizationService.rename(
            organization.getId(), currentUser.getId(), "First Rename", "Original Org", 0L);

        assertThrows(ConflictException.class, () -> organizationService.rename(
            organization.getId(), currentUser.getId(), "Stale Rename", "First Rename", 0L));
        assertEquals("First Rename", organizationMapper.getActiveById(organization.getId()).getName());
    }

    @Test
    void layoutShowsOnlyAuthorizedWorkspaceRostersAndNeverCrossesOrganization() {
        Organization organization = newOrganization("Layout Org");
        orgMemberMapper.addMember(organization.getId(), currentUser.getId(), "owner");
        User authority = newUser();
        orgMemberMapper.addMember(organization.getId(), authority.getId(), "admin");
        Workspace visible = newWorkspace(organization.getId(), "Visible Workspace");
        Workspace restricted = newWorkspace(organization.getId(), "Restricted Workspace");
        workspaceMapper.addMember(visible.getId(), currentUser.getId(), "admin");
        User visibleMember = newUser();
        workspaceMapper.addMember(visible.getId(), visibleMember.getId(), "member");
        User restrictedMember = newUser();
        workspaceMapper.addMember(restricted.getId(), restrictedMember.getId(), "owner");

        Organization foreignOrganization = newOrganization("Foreign Org");
        Workspace foreignWorkspace = newWorkspace(foreignOrganization.getId(), "Foreign Workspace");
        User foreignMember = newUser();
        workspaceMapper.addMember(foreignWorkspace.getId(), foreignMember.getId(), "owner");

        OrganizationLayoutDto layout = organizationService.getLayout(
            organization.getId(), currentUser.getId(), 0, 0, 100);

        assertEquals(organization.getId(), layout.organization().id());
        assertTrue(layout.authorityMemberships().stream()
            .anyMatch(member -> member.getUserId() == authority.getId()));
        OrganizationLayoutWorkspaceDto visibleNode = layout.workspaces().stream()
            .filter(node -> node.id() == visible.getId())
            .findFirst()
            .orElseThrow();
        OrganizationLayoutWorkspaceDto restrictedNode = layout.workspaces().stream()
            .filter(node -> node.id() == restricted.getId())
            .findFirst()
            .orElseThrow();
        assertTrue(visibleNode.rosterVisible());
        assertTrue(visibleNode.memberships().stream()
            .anyMatch(member -> member.getUserId() == visibleMember.getId()));
        assertFalse(visibleNode.memberships().stream()
            .anyMatch(member -> member.getUserId() == restrictedMember.getId()));
        assertFalse(restrictedNode.rosterVisible());
        assertTrue(restrictedNode.memberships().isEmpty());
        assertFalse(restrictedNode.membershipsTruncated());
        assertFalse(layout.workspaces().stream()
            .anyMatch(node -> node.id() == foreignWorkspace.getId()));
        assertFalse(layout.workspaces().stream()
            .flatMap(node -> node.memberships().stream())
            .anyMatch(member -> member.getUserId() == foreignMember.getId()));
    }

    @Test
    void layoutRequiresOrgAuthorityEvenForWorkspaceOwner() {
        Organization organization = newOrganization("Workspace Only Org");
        Workspace child = newWorkspace(organization.getId(), "Owned Workspace");
        workspaceMapper.addMember(child.getId(), currentUser.getId(), "owner");

        assertThrows(ForbiddenException.class, () -> organizationService.getLayout(
            organization.getId(), currentUser.getId(), 0, 0, 50));
    }

    private Organization newOrganization(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setSlug("org-" + unique());
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(int orgId, String name) {
        Workspace child = new Workspace();
        child.setOrgId(orgId);
        child.setName(name);
        child.setSlug("workspace-" + unique());
        workspaceMapper.insert(child);
        return child;
    }
}
