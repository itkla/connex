package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceIdentityDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

class WorkspaceServiceTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void createWorkspace_makesCallerOwner() {
        WorkspaceMembershipDto created = workspaceService.createWorkspace("Acme", currentUser.getId());

        assertNotEquals(0, created.getId());
        assertEquals("owner", created.getRole());
        assertEquals(0L, created.getIdentityVersion());
        assertEquals(0L, created.getOrgIdentityVersion());
        assertTrue(workspaceService.isMember(created.getId(), currentUser.getId()));
        assertEquals("owner", workspaceService.getRole(created.getId(), currentUser.getId()));
    }

    @Test
    void resolvedContext_takesPrecedenceOverFallback() {
        WorkspaceMembershipDto second = workspaceService.createWorkspace("Second", currentUser.getId());

        // Simulate the interceptor pinning the second workspace for this request.
        tenantContext.set(second.getId(), workspaceService.getOrgId(second.getId()), currentUser.getId(), "owner", null);
        try {
            assertEquals(second.getId(), workspaceService.getCurrentWorkspaceId());
        } finally {
            clearRequestContext();
        }
        // Off the request thread it falls back to the first/default membership.
        assertEquals(workspace.getId(), workspaceService.getCurrentWorkspaceId());
    }


    @Test
    void getCurrentOrgId_readsResolvedContext() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Ctx WS", currentUser.getId());
        int orgId = workspaceService.getOrgId(ws.getId());
        tenantContext.set(ws.getId(), orgId, currentUser.getId(), "owner", null);
        try {
            assertEquals(orgId, workspaceService.getCurrentOrgId());
        } finally {
            clearRequestContext();
        }
    }

    @Test
    void requireMember_throwsForbiddenForNonMember() {
        User outsider = newUser(); // member of the default workspace only
        WorkspaceMembershipDto solo = workspaceService.createWorkspace("Solo", currentUser.getId());

        assertThrows(ForbiddenException.class,
            () -> workspaceService.requireMember(solo.getId(), outsider.getId()));
    }

    @Test
    void lockAndRequireMember_passesForActiveMemberAndThrowsForNonMember() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Lock", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");
        User outsider = newUser();

        assertDoesNotThrow(() -> workspaceService.lockAndRequireMember(ws.getId(), member.getId()));
        assertThrows(ForbiddenException.class,
            () -> workspaceService.lockAndRequireMember(ws.getId(), outsider.getId()));
    }

    @Test
    void requireRole_enforcesHierarchy() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Roles", currentUser.getId());

        // owner satisfies admin
        assertDoesNotThrow(() ->
            workspaceService.requireRole(ws.getId(), currentUser.getId(), WorkspaceService.Role.ADMIN));

        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");
        assertThrows(ForbiddenException.class,
            () -> workspaceService.requireRole(ws.getId(), member.getId(), WorkspaceService.Role.ADMIN));
    }

    @Test
    void updateIdentityPersistsCanonicalValuesPreservesSlugAndAuditsExactScope() {
        String slug = workspace.getSlug();
        int orgId = workspaceService.getOrgId(workspace.getId());

        WorkspaceIdentityDto updated = workspaceService.updateIdentity(
            workspace.getId(),
            currentUser.getId(),
            "  Renamed Workspace  ",
            "Pacific/Honolulu",
            workspace.getName(),
            null,
            0L);

        assertEquals("Renamed Workspace", updated.name());
        assertEquals("Pacific/Honolulu", updated.timezone());
        assertEquals(slug, updated.slug());
        assertEquals(1L, updated.identityVersion());
        Workspace persisted = workspaceMapper.getActiveById(workspace.getId());
        assertEquals("Renamed Workspace", persisted.getName());
        assertEquals("Pacific/Honolulu", persisted.getTimezone());
        assertEquals(1L, persisted.getIdentityVersion());
        assertEquals(slug, persisted.getSlug());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'workspace.rename' AND entity_id = ? "
                + "AND workspace_id = ? AND org_id = ?",
            Integer.class,
            workspace.getId(),
            workspace.getId(),
            orgId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'workspace.settings.update' AND entity_id = ? "
                + "AND workspace_id = ? AND org_id = ?",
            Integer.class,
            workspace.getId(),
            workspace.getId(),
            orgId));
        String changes = jdbcTemplate.queryForObject(
            "SELECT changes FROM audit_log WHERE action = 'workspace.rename' AND entity_id = ? "
                + "ORDER BY id DESC LIMIT 1",
            String.class,
            workspace.getId());
        assertTrue(changes.contains("Renamed Workspace"));
    }

    @Test
    void updateIdentitySupportsLegacyNullAndExplicitTimezoneClear() {
        assertNull(workspaceMapper.getActiveById(workspace.getId()).getTimezone());
        workspaceService.updateIdentity(
            workspace.getId(),
            currentUser.getId(),
            workspace.getName(),
            "America/New_York",
            workspace.getName(),
            null,
            0L);

        WorkspaceIdentityDto cleared = workspaceService.updateIdentity(
            workspace.getId(),
            currentUser.getId(),
            workspace.getName(),
            null,
            workspace.getName(),
            "America/New_York",
            1L);

        assertNull(cleared.timezone());
        assertEquals(2L, cleared.identityVersion());
        assertNull(workspaceMapper.getActiveById(workspace.getId()).getTimezone());
    }

    @Test
    void updateIdentityRejectsInvalidNamesAndTimezones() {
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), null, null, workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), " ", null, workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), "x".repeat(129), null, workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), workspace.getName(), " ", workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), workspace.getName(), "Not/A_Real_Zone",
            workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), workspace.getName(), "+09:00",
            workspace.getName(), null, 0L));
        assertThrows(BadRequestException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), currentUser.getId(), workspace.getName(), "x".repeat(65),
            workspace.getName(), null, 0L));
    }

    @Test
    void updateIdentityDeniesMemberWithoutSettingsPermission() {
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class, () -> workspaceService.updateIdentity(
            workspace.getId(), member.getId(), "Denied", null, workspace.getName(), null, 0L));
        assertEquals(workspace.getName(), workspaceMapper.getActiveById(workspace.getId()).getName());
    }

    @Test
    void updateIdentityDeniesWorkspaceInAnotherOrganizationWithoutMembership() {
        Organization organization = new Organization();
        organization.setName("Foreign Org");
        organization.setSlug("foreign-org-" + unique());
        organizationMapper.insert(organization);
        Workspace foreign = new Workspace();
        foreign.setOrgId(organization.getId());
        foreign.setName("Foreign Workspace");
        foreign.setSlug("foreign-workspace-" + unique());
        workspaceMapper.insert(foreign);
        User foreignOwner = newUser();
        workspaceMapper.addMember(foreign.getId(), foreignOwner.getId(), "owner");

        assertThrows(ForbiddenException.class, () -> workspaceService.updateIdentity(
            foreign.getId(), currentUser.getId(), "Probe", "UTC", "Foreign Workspace", null, 0L));
        assertEquals("Foreign Workspace", workspaceMapper.getActiveById(foreign.getId()).getName());
    }
}
