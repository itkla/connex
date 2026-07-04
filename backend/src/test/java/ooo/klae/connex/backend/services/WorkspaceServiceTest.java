package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.tenant.TenantContext;

class WorkspaceServiceTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;

    @Test
    void createWorkspace_makesCallerOwner() {
        WorkspaceMembershipDto created = workspaceService.createWorkspace("Acme", currentUser.getId());

        assertNotEquals(0, created.getId());
        assertEquals("owner", created.getRole());
        assertTrue(workspaceService.isMember(created.getId(), currentUser.getId()));
        assertEquals("owner", workspaceService.getRole(created.getId(), currentUser.getId()));
    }

    @Test
    void resolvedContext_takesPrecedenceOverFallback() {
        WorkspaceMembershipDto second = workspaceService.createWorkspace("Second", currentUser.getId());

        // Simulate the interceptor pinning the second workspace for this request.
        tenantContext.set(second.getId(), workspaceService.getOrgId(second.getId()), currentUser.getId(), "owner");
        try {
            assertEquals(second.getId(), workspaceService.getCurrentWorkspaceId());
        } finally {
            tenantContext.clear();
        }
        // Off the request thread it falls back to the first/default membership.
        assertEquals(workspace.getId(), workspaceService.getCurrentWorkspaceId());
    }


    @Test
    void getCurrentOrgId_readsResolvedContext() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Ctx WS", currentUser.getId());
        int orgId = workspaceService.getOrgId(ws.getId());
        tenantContext.set(ws.getId(), orgId, currentUser.getId(), "owner");
        try {
            assertEquals(orgId, workspaceService.getCurrentOrgId());
        } finally {
            tenantContext.clear();
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
}
