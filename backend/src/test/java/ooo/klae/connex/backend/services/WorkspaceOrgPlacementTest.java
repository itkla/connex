package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Organization-placement rule for new workspaces (#97, #313): the active
 * workspace's org is reused only when the creator is an owner or admin there;
 * a plain member (e.g. an external collaborator inside a client's org), an
 * unresolved context (registration, bootstrap), or a context belonging to a
 * different user always mints a fresh organization. Guards the consultant
 * escalation: a guest membership must never place a personal workspace — and
 * with it, org-wide reach like SSO management — inside the host organization.
 */
class WorkspaceOrgPlacementTest extends AbstractServiceTest {

    @Autowired private WorkspaceService workspaceService;
    @Autowired private TenantContext tenantContext;

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
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

        WorkspaceMembershipDto created = workspaceService.createWorkspace("Fresh " + unique(), currentUser.getId());

        assertNotEquals(existingOrg, workspaceService.getOrgId(created.getId()),
            "without an administrative context a new workspace mints its own organization");
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
