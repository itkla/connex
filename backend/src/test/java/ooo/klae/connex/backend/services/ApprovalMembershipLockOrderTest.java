package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class ApprovalMembershipLockOrderTest {
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
    @InjectMocks private WorkspaceService workspaceService;

    @Test
    void locksActorAndDiscoveredRecipientsInOneSortedHierarchyPass() {
        when(userMapper.lockByIdForShare(3)).thenReturn(3);
        when(userMapper.lockByIdForShare(7)).thenReturn(7);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(11)).thenReturn(11);
        when(workspaceMapper.lockAuthorizationMembership(11, 3))
            .thenReturn(member(11, 3, "member"));
        when(workspaceMapper.lockAuthorizationMembership(11, 7))
            .thenReturn(member(11, 7, "admin"));
        when(workspaceMapper.lockAuthorizationMembership(11, 9))
            .thenReturn(member(11, 9, "member"));

        Set<Permission> permissions = workspaceService.lockApprovalMutationMemberships(
            11, 7, Set.of(9, 3));

        InOrder order = inOrder(userMapper, workspaceMapper);
        order.verify(userMapper).lockByIdForShare(3);
        order.verify(userMapper).lockByIdForShare(7);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(11);
        order.verify(workspaceMapper).lockAuthorizationMembership(11, 3);
        order.verify(workspaceMapper).lockAuthorizationMembership(11, 7);
        order.verify(workspaceMapper).lockAuthorizationMembership(11, 9);
        assertTrue(permissions.contains(Permission.DOCUMENT_APPROVE));
    }

    private static WorkspaceMember member(int workspaceId, int userId, String role) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setStatus("active");
        member.setRole(role);
        return member;
    }
}
