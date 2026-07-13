package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

class RbacTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired RoleService roleService;
    @Autowired RoleMapper roleMapper;

    @Test
    void builtInRolesMapToExpectedPermissions() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("RBAC WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        Set<Permission> ownerPerms = workspaceService.permissionsFor(ws.getId(), currentUser.getId());
        assertTrue(ownerPerms.contains(Permission.ROLE_MANAGE));
        assertFalse(ownerPerms.contains(Permission.WORKSPACE_DELETE));
        assertFalse(ownerPerms.contains(Permission.SSO_MANAGE));

        Set<Permission> memberPerms = workspaceService.permissionsFor(ws.getId(), member.getId());
        assertTrue(memberPerms.contains(Permission.DEAL_DELETE));
        assertTrue(memberPerms.contains(Permission.PERSON_CREATE));
        assertTrue(memberPerms.containsAll(Set.of(
            Permission.REPORT_READ,
            Permission.REPORT_CREATE,
            Permission.REPORT_UPDATE,
            Permission.REPORT_DELETE)));
        assertFalse(memberPerms.contains(Permission.COMPANY_DELETE));
        assertFalse(memberPerms.contains(Permission.TAG_MANAGE));
        assertFalse(memberPerms.contains(Permission.MEMBER_MANAGE));
    }

    @Test
    void customRoleReplacesBuiltInPermissions() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Custom WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        WorkspaceRole role = roleService.createRole(ws.getId(), currentUser.getId(), "Creator",
            List.of("COMPANY_CREATE", "PERSON_CREATE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), member.getId(), role.getId());

        Set<Permission> perms = workspaceService.permissionsFor(ws.getId(), member.getId());
        assertEquals(Set.of(Permission.COMPANY_CREATE, Permission.PERSON_CREATE), perms);

        assertThrows(ForbiddenException.class,
            () -> workspaceService.requirePermission(ws.getId(), member.getId(), Permission.DEAL_DELETE));
        assertDoesNotThrow(
            () -> workspaceService.requirePermission(ws.getId(), member.getId(), Permission.COMPANY_CREATE));
    }

    @Test
    void deletingCustomRoleRevertsMemberToBuiltIn() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Revert WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");
        WorkspaceRole role = roleService.createRole(ws.getId(), currentUser.getId(), "Tmp",
            List.of("COMPANY_CREATE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), member.getId(), role.getId());

        roleService.deleteRole(ws.getId(), currentUser.getId(), role.getId());

        Set<Permission> perms = workspaceService.permissionsFor(ws.getId(), member.getId());
        assertTrue(perms.contains(Permission.DEAL_DELETE));
    }

    @Test
    void roleManagementRequiresPermission() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Gate WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        assertThrows(ForbiddenException.class,
            () -> roleService.createRole(ws.getId(), member.getId(), "Nope", List.of()));
    }

    @Test
    void customRolesCannotGrantInertPermissions() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Inert Permission WS", currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> roleService.createRole(ws.getId(), currentUser.getId(), "Delete Workspace",
                List.of("WORKSPACE_DELETE")));
        assertThrows(BadRequestException.class,
            () -> roleService.createRole(ws.getId(), currentUser.getId(), "SSO Manager",
                List.of("SSO_MANAGE")));
    }

    @Test
    void inertPermissionRowsDoNotReadBackOrAuthorize() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Stale Inert Permission WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");
        WorkspaceRole role = roleService.createRole(ws.getId(), currentUser.getId(), "Stale",
            List.of("COMPANY_CREATE"));

        roleMapper.insertPermissions(ws.getId(), role.getId(), List.of("SSO_MANAGE", "WORKSPACE_DELETE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), member.getId(), role.getId());

        WorkspaceRole readBack = roleService.listRoles(ws.getId(), currentUser.getId()).stream()
            .filter(candidate -> candidate.getId() == role.getId())
            .findFirst()
            .orElseThrow();
        assertEquals(List.of("COMPANY_CREATE"), readBack.getPermissions());
        assertEquals(Set.of(Permission.COMPANY_CREATE), workspaceService.permissionsFor(ws.getId(), member.getId()));
    }

    @Test
    void roleManagementRequiresRecentWebAuthnStepUp() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Step Up WS", currentUser.getId());
        RequestContextHolder.resetRequestAttributes();

        assertThrows(ForbiddenException.class,
            () -> roleService.createRole(ws.getId(), currentUser.getId(), "No Step Up", List.of()));
    }

    @Test
    void adminCannotDemoteAnOwnerWithoutOwnerRole() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Owner Guard WS", currentUser.getId());
        User coOwner = newUser();
        workspaceMapper.addMember(ws.getId(), coOwner.getId(), "owner");
        User admin = newUser();
        workspaceMapper.addMember(ws.getId(), admin.getId(), "admin");

        assertThrows(ForbiddenException.class,
            () -> workspaceService.changeMemberRole(ws.getId(), admin.getId(), coOwner.getId(), "member"));

        assertDoesNotThrow(
            () -> workspaceService.changeMemberRole(ws.getId(), currentUser.getId(), coOwner.getId(), "member"));
    }

    @Test
    void roleManageCannotMintRoleGrantingPermissionsActorLacks() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Ceiling WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole roleAdmin = roleService.createRole(ws.getId(), currentUser.getId(), "RoleAdmin",
            List.of("ROLE_MANAGE", "PERSON_CREATE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), delegate.getId(), roleAdmin.getId());
        authenticateAs(delegate, ws.getId());

        assertThrows(ForbiddenException.class,
            () -> roleService.createRole(ws.getId(), delegate.getId(), "SuperRole", List.of("MEMBER_MANAGE")));
        assertDoesNotThrow(
            () -> roleService.createRole(ws.getId(), delegate.getId(), "NarrowRole", List.of("PERSON_CREATE")));
    }

    @Test
    void roleManageCannotSelfAssignRoleBroaderThanActor() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Assign Ceiling WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole roleAdmin = roleService.createRole(ws.getId(), currentUser.getId(), "RoleAdmin",
            List.of("ROLE_MANAGE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), delegate.getId(), roleAdmin.getId());
        WorkspaceRole superRole = roleService.createRole(ws.getId(), currentUser.getId(), "Super",
            List.of("MEMBER_MANAGE", "WORKSPACE_SETTINGS"));
        authenticateAs(delegate, ws.getId());

        assertThrows(ForbiddenException.class,
            () -> workspaceService.assignCustomRole(ws.getId(), delegate.getId(), delegate.getId(), superRole.getId()));
    }

    @Test
    void memberManageDelegateCannotPromoteToBuiltInAdmin() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Promote Ceiling WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole hrRole = roleService.createRole(ws.getId(), currentUser.getId(), "HR",
            List.of("MEMBER_MANAGE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), delegate.getId(), hrRole.getId());
        authenticateAs(delegate, ws.getId());

        assertThrows(ForbiddenException.class,
            () -> workspaceService.changeMemberRole(ws.getId(), delegate.getId(), delegate.getId(), "admin"));

        assertFalse(workspaceService.permissionsFor(ws.getId(), delegate.getId()).contains(Permission.WORKSPACE_SETTINGS));
    }
}
