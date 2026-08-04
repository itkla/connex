package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.WorkspaceIdentityDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Pins workspace identity mutation to the user-workspace-membership-role lock hierarchy. */
@ExtendWith(MockitoExtension.class)
class WorkspaceIdentityLockOrderTest {
    private static final int WORKSPACE_ID = 7;
    private static final int ACTOR_ID = 11;

    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private OrgAllowedDomainService orgAllowedDomainService;
    @Mock private RoleMapper roleMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private UserOffboardingService userOffboardingService;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationStateVersionService notificationStateVersionService;
    @Mock private TenantContext tenantContext;
    @Mock private AuditService auditService;
    @Mock private SystemActor systemActor;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private WorkspaceService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analyticsTimezoneUsesWorkspaceOverrideThenPersistedActorFallback() {
        User principal = user("UTC");
        User persisted = user("Asia/Tokyo");
        Workspace workspace = workspace("Workspace", "Pacific/Honolulu");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(tenantContext.isResolved()).thenReturn(true);
        when(tenantContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(workspaceMapper.getActiveById(WORKSPACE_ID)).thenReturn(workspace);
        when(userMapper.getUserById(ACTOR_ID)).thenReturn(persisted);

        assertEquals("Pacific/Honolulu", service.getCurrentAnalyticsTimezone());

        workspace.setTimezone(null);
        assertEquals("Asia/Tokyo", service.getCurrentAnalyticsTimezone());

        persisted.setTimezone(null);
        assertEquals("UTC", service.getCurrentAnalyticsTimezone());
    }

    @Test
    void mutationLocksAndRevalidatesBeforeWritingAndScopedAudit() {
        Workspace before = workspace("Old Name", null);
        Workspace after = workspace("New Name", null);
        WorkspaceMember membership = membership("admin", null, "active");
        when(workspaceMapper.getMemberRoleId(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(null);
        when(workspaceMapper.getRole(WORKSPACE_ID, ACTOR_ID)).thenReturn("admin");
        when(userMapper.lockById(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(workspaceMapper.lockActiveIdentity(WORKSPACE_ID)).thenReturn(before);
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(membership);
        when(workspaceMapper.updateIdentity(WORKSPACE_ID, "New Name", null)).thenReturn(1);
        when(workspaceMapper.getActiveById(WORKSPACE_ID)).thenReturn(after);

        WorkspaceIdentityDto result = service.updateIdentity(
            WORKSPACE_ID, ACTOR_ID, "New Name", null);

        assertEquals("New Name", result.name());
        InOrder order = inOrder(
            userMapper, workspaceMapper, sessionSecurityService, auditService);
        order.verify(userMapper).isAccountDeletionReserved(ACTOR_ID);
        order.verify(workspaceMapper).getMemberRoleId(WORKSPACE_ID, ACTOR_ID);
        order.verify(workspaceMapper).getRole(WORKSPACE_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(userMapper).lockById(ACTOR_ID);
        order.verify(userMapper).isAccountDeletionReserved(ACTOR_ID);
        order.verify(workspaceMapper).lockActiveIdentity(WORKSPACE_ID);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID);
        order.verify(workspaceMapper).updateIdentity(WORKSPACE_ID, "New Name", null);
        order.verify(auditService).singleChange("name", "Old Name", "New Name");
        order.verify(auditService).recordScoped(
            eq("workspace.rename"),
            eq("workspace"),
            eq(WORKSPACE_ID),
            eq(WORKSPACE_ID),
            eq(3),
            eq("New Name"),
            eq("Renamed workspace"),
            any());
        order.verify(workspaceMapper).getActiveById(WORKSPACE_ID);
    }

    @Test
    void finalLockedCustomRolePermissionRevocationRefusesMutation() {
        Workspace before = workspace("Old Name", null);
        WorkspaceMember membership = membership("member", 5, "active");
        when(workspaceMapper.getMemberRoleId(WORKSPACE_ID, ACTOR_ID)).thenReturn(5);
        when(roleMapper.findPermissions(WORKSPACE_ID, 5))
            .thenReturn(List.of(Permission.WORKSPACE_SETTINGS.name()));
        when(userMapper.lockById(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(workspaceMapper.lockActiveIdentity(WORKSPACE_ID)).thenReturn(before);
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(membership);
        when(roleMapper.lockRole(WORKSPACE_ID, 5)).thenReturn(5);
        when(roleMapper.lockPermissions(WORKSPACE_ID, 5)).thenReturn(List.of());

        assertThrows(ForbiddenException.class, () -> service.updateIdentity(
            WORKSPACE_ID, ACTOR_ID, "New Name", null));

        InOrder order = inOrder(userMapper, workspaceMapper, roleMapper);
        order.verify(userMapper).lockById(ACTOR_ID);
        order.verify(workspaceMapper).lockActiveIdentity(WORKSPACE_ID);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID);
        order.verify(roleMapper).lockRole(WORKSPACE_ID, 5);
        order.verify(roleMapper).lockPermissions(WORKSPACE_ID, 5);
        verify(workspaceMapper, never()).updateIdentity(any(Integer.class), any(), any());
        verify(auditService, never()).recordScoped(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void finalLockedMembershipRevocationRefusesMutation() {
        when(workspaceMapper.getMemberRoleId(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(null);
        when(workspaceMapper.getRole(WORKSPACE_ID, ACTOR_ID)).thenReturn("admin");
        when(userMapper.lockById(ACTOR_ID)).thenReturn(ACTOR_ID);
        when(workspaceMapper.lockActiveIdentity(WORKSPACE_ID))
            .thenReturn(workspace("Old Name", null));
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(membership("admin", null, "pending"));

        assertThrows(ForbiddenException.class, () -> service.updateIdentity(
            WORKSPACE_ID, ACTOR_ID, "New Name", null));

        verify(workspaceMapper, never()).updateIdentity(any(Integer.class), any(), any());
    }

    private static Workspace workspace(String name, String timezone) {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setOrgId(3);
        workspace.setName(name);
        workspace.setSlug("immutable");
        workspace.setTimezone(timezone);
        workspace.setUpdatedAt("2026-08-03 12:00:00");
        return workspace;
    }

    private static WorkspaceMember membership(String role, Integer roleId, String status) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(WORKSPACE_ID);
        membership.setUserId(ACTOR_ID);
        membership.setRole(role);
        membership.setRoleId(roleId);
        membership.setStatus(status);
        return membership;
    }

    private static User user(String timezone) {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setUsername("actor");
        user.setTimezone(timezone);
        return user;
    }
}
