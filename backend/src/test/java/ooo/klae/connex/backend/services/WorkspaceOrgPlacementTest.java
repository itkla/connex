package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Organization-placement rule for new workspaces (#97, #313): the active
 * workspace's org is reused only when the creator holds
 * {@code WORKSPACE_SETTINGS} there (built-in owner/admin, or a custom role
 * granting it — resolved live through the permission catalog); a plain member
 * (e.g. an external collaborator inside a client's org), an unresolved context
 * (registration, bootstrap), or a context belonging to a different user always
 * mints a fresh organization. Guards the consultant escalation: a guest
 * membership must never place a personal workspace — and with it, org-wide
 * reach like SSO management — inside the host organization.
 */
class WorkspaceOrgPlacementTest extends AbstractServiceTest {

    @Autowired private WorkspaceService workspaceService;
    @Autowired private TenantContext tenantContext;
    @Autowired private RoleMapper roleMapper;

    @AfterEach
    void clearTenantContext() {
        clearRequestContext();
    }

    private void assignCustomRole(User member, String roleName, String permission) {
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspace.getId());
        role.setName(roleName + " " + unique());
        roleMapper.insertRole(role);
        roleMapper.insertPermissions(workspace.getId(), role.getId(), List.of(permission));
        workspaceMapper.setMemberCustomRole(workspace.getId(), member.getId(), role.getId());
    }

    @Test
    void ownerCreatingWorkspace_staysInTheActiveOrg() {
        int activeOrg = workspaceService.getOrgId(workspace.getId());
        tenantContext.set(workspace.getId(), activeOrg, currentUser.getId(), "owner");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Team " + unique(), currentUser.getId());

        assertEquals(activeOrg, workspaceService.getOrgId(created.getId()),
            "an owner's new workspace expands their own organization");
    }

    @Test
    void adminCreatingWorkspace_staysInTheActiveOrg() {
        int activeOrg = workspaceService.getOrgId(workspace.getId());
        tenantContext.set(workspace.getId(), activeOrg, currentUser.getId(), "admin");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Team " + unique(), currentUser.getId());

        assertEquals(activeOrg, workspaceService.getOrgId(created.getId()));
    }

    @Test
    void plainMemberCreatingWorkspace_getsAFreshOrg() {
        User consultant = newUser();
        int hostOrg = workspaceService.getOrgId(workspace.getId());
        tenantContext.set(workspace.getId(), hostOrg, consultant.getId(), "member");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Personal " + unique(), consultant.getId());

        assertNotEquals(hostOrg, workspaceService.getOrgId(created.getId()),
            "a guest membership must never pull a personal workspace into the host org");
    }

    @Test
    void unresolvedContext_getsAFreshOrg() {
        int existingOrg = workspaceService.getOrgId(workspace.getId());
        clearRequestContext();

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Fresh " + unique(), currentUser.getId());

        assertNotEquals(existingOrg, workspaceService.getOrgId(created.getId()),
            "without an administrative context a new workspace mints its own organization");
    }

    @Test
    void customRoleGrantingWorkspaceSettings_staysInTheActiveOrg() {
        User delegate = newUser();
        int activeOrg = workspaceService.getOrgId(workspace.getId());
        assignCustomRole(delegate, "Org Delegate", "WORKSPACE_SETTINGS");
        tenantContext.set(workspace.getId(), activeOrg, delegate.getId(), "member");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Delegated " + unique(), delegate.getId());

        assertEquals(activeOrg, workspaceService.getOrgId(created.getId()),
            "placement follows the permission catalog, not the built-in role string");
    }

    @Test
    void customRoleWithoutWorkspaceSettings_getsAFreshOrg() {
        User collaborator = newUser();
        int activeOrg = workspaceService.getOrgId(workspace.getId());
        assignCustomRole(collaborator, "Sharing Only", "SHARE_MANAGE");
        tenantContext.set(workspace.getId(), activeOrg, collaborator.getId(), "member");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Solo " + unique(), collaborator.getId());

        assertNotEquals(activeOrg, workspaceService.getOrgId(created.getId()));
    }

    @Test
    void contextBelongingToAnotherUser_getsAFreshOrg() {
        User other = newUser();
        int activeOrg = workspaceService.getOrgId(workspace.getId());
        tenantContext.set(workspace.getId(), activeOrg, currentUser.getId(), "owner");

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Other " + unique(), other.getId());

        assertNotEquals(activeOrg, workspaceService.getOrgId(created.getId()),
            "an administrative context only places workspaces created by that same user");
    }
}
