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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

class RbacTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired RoleService roleService;
    @Autowired RoleMapper roleMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void builtInRolesMapToExpectedPermissions() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("RBAC WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        Set<Permission> ownerPerms = workspaceService.permissionsFor(ws.getId(), currentUser.getId());
        assertTrue(ownerPerms.contains(Permission.ROLE_MANAGE));
        assertTrue(ownerPerms.contains(Permission.CAMPAIGN_MANAGE));
        assertTrue(ownerPerms.contains(Permission.CONSENT_MANAGE));
        assertTrue(ownerPerms.contains(Permission.AI_SESSION_SHARE));
        assertTrue(ownerPerms.contains(Permission.AI_SESSION_ADMIN));
        assertFalse(ownerPerms.contains(Permission.WORKSPACE_DELETE));
        assertFalse(ownerPerms.contains(Permission.SSO_MANAGE));

        Set<Permission> memberPerms = workspaceService.permissionsFor(ws.getId(), member.getId());
        assertTrue(memberPerms.contains(Permission.DEAL_DELETE));
        assertTrue(memberPerms.contains(Permission.PERSON_CREATE));
        assertTrue(memberPerms.containsAll(Set.of(
            Permission.REPORT_READ,
            Permission.REPORT_CREATE,
            Permission.REPORT_UPDATE,
            Permission.REPORT_DELETE,
            Permission.COMMENT_CREATE,
            Permission.COMMENT_MODERATE,
            Permission.CAMPAIGN_VIEW,
            Permission.SEQUENCE_VIEW)));
        assertFalse(memberPerms.contains(Permission.CAMPAIGN_MANAGE));
        assertFalse(memberPerms.contains(Permission.CONSENT_MANAGE));
        assertFalse(memberPerms.contains(Permission.COMPANY_DELETE));
        assertFalse(memberPerms.contains(Permission.TAG_MANAGE));
        assertFalse(memberPerms.contains(Permission.MEMBER_MANAGE));
        assertFalse(memberPerms.contains(Permission.AI_SESSION_SHARE));
        assertFalse(memberPerms.contains(Permission.AI_SESSION_ADMIN));
        assertFalse(memberPerms.contains(Permission.SEQUENCE_MANAGE));

        User admin = newUser();
        workspaceMapper.addMember(ws.getId(), admin.getId(), "member");
        workspaceMapper.updateMemberRole(ws.getId(), admin.getId(), "admin");
        assertTrue(workspaceService.permissionsFor(ws.getId(), admin.getId())
            .containsAll(Set.of(
                Permission.AI_SESSION_SHARE,
                Permission.AI_SESSION_ADMIN,
                Permission.SEQUENCE_VIEW,
                Permission.SEQUENCE_MANAGE)));
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
    void aiSessionAdminIsGrantableToACustomRole() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Assistant Oversight WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        WorkspaceRole role = roleService.createRole(
            ws.getId(), currentUser.getId(), "Assistant auditor",
            List.of(Permission.AI_SESSION_ADMIN.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), member.getId(), role.getId());

        assertEquals(
            Set.of(Permission.AI_SESSION_ADMIN),
            workspaceService.permissionsFor(ws.getId(), member.getId()));
    }

    @Test
    void deletingAnAssignedCustomRoleRequiresExplicitReassignment() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Revert WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");
        WorkspaceRole role = roleService.createRole(ws.getId(), currentUser.getId(), "Tmp",
            List.of("COMPANY_CREATE"));
        workspaceService.assignCustomRole(ws.getId(), currentUser.getId(), member.getId(), role.getId());

        assertThrows(
            BadRequestException.class,
            () -> roleService.deleteRole(ws.getId(), currentUser.getId(), role.getId()));
        assertEquals(
            Set.of(Permission.COMPANY_CREATE),
            workspaceService.permissionsFor(ws.getId(), member.getId()));

        workspaceService.changeMemberRole(ws.getId(), currentUser.getId(), member.getId(), "member");
        roleService.deleteRole(ws.getId(), currentUser.getId(), role.getId());

        Set<Permission> perms = workspaceService.permissionsFor(ws.getId(), member.getId());
        assertTrue(perms.contains(Permission.DEAL_DELETE));
    }

    @Test
    void customRoleRoundTripDoesNotAuthorizeALegacyPendingAdminGrant() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Legacy Pending Grant WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole roleManager = roleService.createRole(
            ws.getId(), currentUser.getId(), "Pending Role Manager",
            List.of(Permission.ROLE_MANAGE.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), delegate.getId(), roleManager.getId());
        WorkspaceRole emptyRole = roleService.createRole(
            ws.getId(), currentUser.getId(), "Temporary Pending Overlay", List.of());
        User pending = newUser();
        workspaceMapper.addPendingMember(ws.getId(), pending.getId(), "admin");
        jdbcTemplate.update(
            "UPDATE workspace_member SET grant_authorization_version = 0 "
                + "WHERE workspace_id = ? AND user_id = ?",
            ws.getId(), pending.getId());
        authenticateAs(delegate, ws.getId());

        workspaceService.assignCustomRole(
            ws.getId(), delegate.getId(), pending.getId(), emptyRole.getId());

        assertThrows(
            BadRequestException.class,
            () -> roleService.deleteRole(ws.getId(), delegate.getId(), emptyRole.getId()));
        assertThrows(
            ResourceNotFoundException.class,
            () -> workspaceService.approveMembership(ws.getId(), pending.getId()));
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

        assertEquals(3, roleService.builtInRoles(ws.getId(), delegate.getId()).size());
        assertThrows(ForbiddenException.class,
            () -> workspaceService.changeMemberRole(ws.getId(), delegate.getId(), delegate.getId(), "admin"));

        assertFalse(workspaceService.permissionsFor(ws.getId(), delegate.getId()).contains(Permission.WORKSPACE_SETTINGS));
    }

    @Test
    void customOwnerOverlayKeepsRawOwnerVisibleAndProtected() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Custom Owner Guard WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole memberManager = roleService.createRole(
            ws.getId(), currentUser.getId(), "Member Manager",
            List.of(Permission.MEMBER_MANAGE.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), delegate.getId(), memberManager.getId());
        WorkspaceRole ownerOverlay = roleService.createRole(
            ws.getId(), currentUser.getId(), "Owner Overlay",
            List.of(Permission.PERSON_CREATE.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), currentUser.getId(), ownerOverlay.getId());

        var owner = workspaceService.getMembersWithRoles(ws.getId(), delegate.getId()).stream()
            .filter(member -> member.getId() == currentUser.getId())
            .findFirst()
            .orElseThrow();
        assertEquals("Owner Overlay", owner.getRole());
        assertEquals("owner", owner.getBuiltInRole());

        authenticateAs(delegate, ws.getId());
        assertThrows(
            ForbiddenException.class,
            () -> workspaceService.removeMember(ws.getId(), delegate.getId(), currentUser.getId()));
        assertTrue(workspaceMapper.isMember(ws.getId(), currentUser.getId()));
    }

    @Test
    void nonOwnerRoleManagerCannotAssignCustomRoleToOwner() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Owner Overlay Assignment WS", currentUser.getId());
        User delegate = newUser();
        workspaceMapper.addMember(ws.getId(), delegate.getId(), "member");
        WorkspaceRole roleManager = roleService.createRole(
            ws.getId(), currentUser.getId(), "Role Manager",
            List.of(Permission.ROLE_MANAGE.name(), Permission.PERSON_CREATE.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), delegate.getId(), roleManager.getId());
        WorkspaceRole requested = roleService.createRole(
            ws.getId(), currentUser.getId(), "Contact Creator",
            List.of(Permission.PERSON_CREATE.name()));
        authenticateAs(delegate, ws.getId());

        assertThrows(
            ForbiddenException.class,
            () -> workspaceService.assignCustomRole(
                ws.getId(), delegate.getId(), currentUser.getId(), requested.getId()));
    }
}
