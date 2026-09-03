package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
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
class WorkspaceNotificationLockOrderTest {
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private OrgAllowedDomainService orgAllowedDomainService;
    @Mock private RoleMapper roleMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private UserOffboardingService userOffboardingService;
    @Mock private WorkspaceMembershipRemovalTransaction membershipRemovalTransaction;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private TenantContext tenantContext;
    @Mock private AuditService auditService;
    @Mock private SystemActor systemActor;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private WorkspaceService service;

    private void runMembershipRemovalWork() {
        doAnswer(invocation -> {
            int workspaceId = invocation.getArgument(0);
            Supplier<String> authorization = invocation.getArgument(2);
            authorization.get();
            BiFunction<Integer, Integer, ?> work = invocation.getArgument(3);
            return work.apply(workspaceId, 41);
        }).when(membershipRemovalTransaction).execute(
            anyInt(), anyInt(), any(), any());
    }

    @Test
    void declineRunsRoutedCleanupBeforeMembershipDeleteAndStateChange() {
        runMembershipRemovalWork();
        MemberDto pending = new MemberDto();
        pending.setBuiltInRole("member");
        pending.setStatus("pending");
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.getMember(7, 9)).thenReturn(pending);

        service.declineMembership(7, 9);

        InOrder order = inOrder(
            userMapper, userOffboardingService, workspaceMapper, stateVersionService);
        order.verify(userMapper).lockById(9);
        order.verify(userOffboardingService).detachMemberContent(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void ownerLeaveLocksWorkspaceAndMembershipsBeforeOwnerRowsAndNotifications() {
        runMembershipRemovalWork();
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.getRole(7, 9)).thenReturn("owner");
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.leaveWorkspace(7, 9);

        InOrder order = inOrder(
            userMapper, workspaceMapper, notificationMapper, userOffboardingService, stateVersionService);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(userOffboardingService).detachMemberContent(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void ownerRemovalLocksUsersWorkspaceAndMembershipsBeforeOwnerRowsAndCleanup() {
        runMembershipRemovalWork();
        stubOwnerActor();
        stubRoleMutationTarget("owner");
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.removeMember(7, 1, 9);

        InOrder order = inOrder(
            userMapper,
            notificationMapper,
            workspaceMapper,
            userOffboardingService,
            stateVersionService);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(workspaceMapper).getMember(7, 9);
        order.verify(userOffboardingService).detachMemberContent(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void memberRemovalRevalidatesCurrentPermissionBeforeCleanup() {
        runMembershipRemovalWork();
        when(workspaceMapper.getMemberRoleId(7, 1)).thenReturn(null);
        when(workspaceMapper.getRole(7, 1)).thenReturn("owner");
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 1))
            .thenReturn(membership(1, "member", null, "active"));
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "member", null, "active"));

        assertThrows(ForbiddenException.class, () -> service.removeMember(7, 1, 9));

        verifyNoInteractions(userOffboardingService);
        verify(workspaceMapper, never()).removeMember(7, 9);
    }

    @Test
    void memberRemovalUsesLockedOwnerState() {
        runMembershipRemovalWork();
        when(workspaceMapper.getMemberRoleId(7, 1)).thenReturn(null);
        when(workspaceMapper.getRole(7, 1)).thenReturn("admin");
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 1))
            .thenReturn(membership(1, "admin", null, "active"));
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "owner", null, "active"));

        assertThrows(ForbiddenException.class, () -> service.removeMember(7, 1, 9));

        verifyNoInteractions(userOffboardingService);
        verify(workspaceMapper, never()).removeMember(7, 9);
    }

    @Test
    void accountGuardLocksOwnedWorkspacesAndMembershipsBeforeOwnerRows() {
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(3)).thenReturn(List.of(9, 11));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(9, 12));

        service.assertNotSoleOwnerOfAnyWorkspace(9);

        InOrder order = inOrder(workspaceMapper, notificationMapper);
        order.verify(workspaceMapper).workspaceIdsOwnedBy(9);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(3);
        order.verify(workspaceMapper).lockOwnerIds(7);
    }

    @Test
    void accountDeletionLocksOwnedAndWorkflowWorkspaceRootsInOneAscendingOrder() {
        service.lockAccountWorkspaceRoots(List.of(9, 3), List.of(7, 2, 9));

        InOrder order = inOrder(workspaceMapper);
        order.verify(workspaceMapper).lockWorkspaceForShare(2);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspaceForShare(7);
        order.verify(workspaceMapper).lockWorkspace(9);
    }

    @Test
    void ownerPromotionLocksSortedUsersWorkspaceAndSortedMembershipsBeforeRoleEvaluation() {
        stubOwnerActor();
        stubRoleMutationTarget("member");

        service.changeMemberRole(7, 1, 9, "owner");

        InOrder order = inOrder(workspaceMapper, userMapper, sessionSecurityService);
        order.verify(workspaceMapper).getMemberRoleId(7, 1);
        order.verify(workspaceMapper).getRole(7, 1);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(workspaceMapper).updateMemberRole(7, 9, "owner");
    }

    @Test
    void builtInRoleChangeLocksUserWorkspaceAndMembershipBeforeMutation() {
        stubOwnerActor();
        stubRoleMutationTarget("member");

        service.changeMemberRole(7, 1, 9, "admin");

        InOrder order = inOrder(workspaceMapper, userMapper, sessionSecurityService);
        order.verify(workspaceMapper).getMemberRoleId(7, 1);
        order.verify(workspaceMapper).getRole(7, 1);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(workspaceMapper).getMember(7, 9);
        order.verify(workspaceMapper).updateMemberRole(7, 9, "admin");
    }

    @Test
    void customRoleAssignmentLocksSortedUsersMembershipsRolesAndCurrentPermissions() {
        WorkspaceMember targetMembership = membership(1, "member", null, "active");
        WorkspaceMember actorMembership = membership(9, "member", 11, "active");
        MemberDto target = member("Target", "member", "active");
        when(workspaceMapper.getMemberRoleId(7, 9)).thenReturn(11);
        when(roleMapper.findPermissions(7, 11))
            .thenReturn(List.of(Permission.PERSON_CREATE.name(), Permission.ROLE_MANAGE.name()));
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 1)).thenReturn(targetMembership);
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(actorMembership);
        when(roleMapper.lockRole(7, 5)).thenReturn(5);
        when(roleMapper.lockRole(7, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(7, 5)).thenReturn(List.of(Permission.PERSON_CREATE.name()));
        when(roleMapper.lockPermissions(7, 11))
            .thenReturn(List.of(Permission.PERSON_CREATE.name(), Permission.ROLE_MANAGE.name()));
        when(workspaceMapper.getMember(7, 1)).thenReturn(target);

        service.assignCustomRole(7, 9, 1, 5);

        InOrder order = inOrder(workspaceMapper, userMapper, roleMapper, sessionSecurityService);
        order.verify(workspaceMapper).getMemberRoleId(7, 9);
        order.verify(roleMapper).findPermissions(7, 11);
        order.verify(sessionSecurityService).requireRecentAuthentication(9);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(roleMapper).lockRole(7, 5);
        order.verify(roleMapper).lockRole(7, 11);
        order.verify(roleMapper).lockPermissions(7, 5);
        order.verify(roleMapper).lockPermissions(7, 11);
        order.verify(workspaceMapper).getMember(7, 1);
        order.verify(workspaceMapper).setMemberCustomRole(7, 1, 5);
    }

    @Test
    void inviteGrantLocksSortedUsersWorkspaceMembershipsAndCurrentRole() {
        WorkspaceMember actorMembership = membership(9, "member", 11, "active");
        Workspace workspace = workspace(7, 3);
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockActiveIdentity(7)).thenReturn(workspace);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(7, 1)).thenReturn(null);
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(actorMembership);
        when(roleMapper.lockRole(7, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(7, 11)).thenReturn(Permission.grantableNames());

        service.lockInviteGrantAuthorization(7, 9, 1, "admin");

        InOrder order = inOrder(workspaceMapper, userMapper, organizationMapper, roleMapper);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockActiveIdentity(7);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(roleMapper).lockRole(7, 11);
        order.verify(roleMapper).lockPermissions(7, 11);
    }

    @Test
    void persistedInviteGrantAllowsAnIdempotentActiveTarget() {
        WorkspaceMember actorMembership = membership(9, "owner", null, "active");
        WorkspaceMember targetMembership = membership(1, "member", null, "active");
        Workspace workspace = workspace(7, 3);
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockActiveIdentity(7)).thenReturn(workspace);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(7, 1)).thenReturn(targetMembership);
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(actorMembership);

        assertTrue(service.lockPersistedInviteGrantAuthorization(7, 9, 1, "member"));
    }

    @Test
    void inviteGrantRejectsCreatorWithReservedAccount() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(userMapper.isAccountDeletionReserved(9)).thenReturn(true);

        assertThrows(
            ForbiddenException.class,
            () -> service.lockInviteGrantAuthorization(7, 9, null, "member"));

        verify(workspaceMapper, never()).lockActiveIdentity(7);
    }

    @Test
    void inviteGrantRejectsRecipientWithReservedAccount() {
        when(userMapper.lockById(1)).thenReturn(1);
        when(userMapper.isAccountDeletionReserved(1)).thenReturn(true);

        assertThrows(
            ooo.klae.connex.backend.exceptions.ConflictException.class,
            () -> service.lockInviteGrantAuthorization(7, 9, 1, "member"));

        verify(userMapper, never()).lockById(9);
        verify(workspaceMapper, never()).lockActiveIdentity(7);
    }

    @Test
    void inviteGrantRejectsInactiveOrganizationBeforeMembershipLocks() {
        Workspace workspace = workspace(7, 3);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockActiveIdentity(7)).thenReturn(workspace);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(null);

        assertThrows(
            ForbiddenException.class,
            () -> service.lockInviteGrantAuthorization(7, 9, null, "member"));

        verify(organizationMapper).lockActiveByIdForShare(3);
        verify(workspaceMapper, never()).lockAuthorizationMembership(7, 9);
    }

    @Test
    void roleDeletionAuthorizationLocksActorWorkspaceMembershipRolesAndPermissions() {
        WorkspaceMember actorMembership = membership(9, "member", 11, "active");
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(actorMembership);
        when(roleMapper.lockRole(7, 5)).thenReturn(5);
        when(roleMapper.lockRole(7, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(7, 5)).thenReturn(List.of(Permission.PERSON_CREATE.name()));
        when(roleMapper.lockPermissions(7, 11)).thenReturn(List.of(Permission.ROLE_MANAGE.name()));

        service.lockRoleDeletionAuthorization(7, 9, 5);

        InOrder order = inOrder(workspaceMapper, userMapper, roleMapper);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(roleMapper).lockRole(7, 5);
        order.verify(roleMapper).lockRole(7, 11);
        order.verify(roleMapper).lockPermissions(7, 5);
        order.verify(roleMapper).lockPermissions(7, 11);
        order.verify(workspaceMapper).hasMembersWithCustomRole(7, 5);
    }

    @Test
    void roleDeletionAuthorizationRejectsAnAssignedRoleAfterAuthorizationLocks() {
        WorkspaceMember actorMembership = membership(9, "owner", null, "active");
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(actorMembership);
        when(roleMapper.lockRole(7, 5)).thenReturn(5);
        when(roleMapper.lockPermissions(7, 5)).thenReturn(List.of());
        when(workspaceMapper.hasMembersWithCustomRole(7, 5)).thenReturn(true);

        assertThrows(
            BadRequestException.class,
            () -> service.lockRoleDeletionAuthorization(7, 9, 5));

        InOrder order = inOrder(workspaceMapper, userMapper, roleMapper);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(roleMapper).lockRole(7, 5);
        order.verify(roleMapper).lockPermissions(7, 5);
        order.verify(workspaceMapper).hasMembersWithCustomRole(7, 5);
    }

    @Test
    void roleDeletionAuthorizationDistinguishesMissingTargetRole() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "owner", null, "active"));
        when(roleMapper.lockRole(7, 5)).thenReturn(null);

        assertThrows(
            ResourceNotFoundException.class,
            () -> service.lockRoleDeletionAuthorization(7, 9, 5));

        verify(roleMapper, never()).lockPermissions(7, 5);
    }

    @Test
    void roleDeletionAuthorizationFailsClosedOnRevokedCurrentPermission() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "member", 11, "active"));
        when(roleMapper.lockRole(7, 5)).thenReturn(5);
        when(roleMapper.lockRole(7, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(7, 5)).thenReturn(List.of(Permission.PERSON_CREATE.name()));
        when(roleMapper.lockPermissions(7, 11)).thenReturn(List.of(Permission.PERSON_CREATE.name()));

        assertThrows(
            ForbiddenException.class,
            () -> service.lockRoleDeletionAuthorization(7, 9, 5));
    }

    @Test
    void ownerDemotionUsesExactUserWorkspaceMembershipOwnerOrder() {
        stubOwnerActor();
        stubRoleMutationTarget("owner");
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.changeMemberRole(7, 1, 9, "member");

        InOrder order = inOrder(workspaceMapper, userMapper);
        order.verify(userMapper).lockById(1);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 1);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(workspaceMapper).updateMemberRole(7, 9, "member");
        verify(workspaceMapper, never()).workspaceIdsOwnedBy(9);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void approveMembershipLocksUserWorkspaceAndPendingMembershipBeforeActivation() {
        WorkspaceMember membership = membership(9, "member", null, "pending");
        MemberDto pending = member("Pending", "member", "pending");
        pending.setEmail("pending@example.com");
        WorkspaceMembershipDto activated = new WorkspaceMembershipDto(7, "Workspace", "workspace", "member");
        when(userMapper.lockById(9)).thenReturn(9);
        stubActiveWorkspaceIdentity();
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(membership);
        when(workspaceMapper.getMember(7, 9)).thenReturn(pending);
        when(orgAllowedDomainService.isJoinAllowed(3, "pending@example.com")).thenReturn(true);
        when(workspaceMapper.activateMember(7, 9)).thenReturn(1);
        when(workspaceMapper.getMembershipsForUser(9)).thenReturn(List.of(activated));

        service.approveMembership(7, 9);

        InOrder order = inOrder(
            userMapper,
            workspaceMapper,
            organizationMapper,
            orgAllowedDomainService,
            stateVersionService,
            auditService);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockActiveIdentity(7);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(workspaceMapper).getMember(7, 9);
        order.verify(orgAllowedDomainService).isJoinAllowed(3, "pending@example.com");
        order.verify(workspaceMapper).activateMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
        order.verify(auditService).record(
            "workspace.member.join", "workspace", 7, null, "Accepted invitation", null);
    }

    @Test
    void approveMembershipRejectsCurrentActiveMembershipBeforeDomainOrActivation() {
        when(userMapper.lockById(9)).thenReturn(9);
        stubActiveWorkspaceIdentity();
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "member", null, "active"));

        assertThrows(ResourceNotFoundException.class, () -> service.approveMembership(7, 9));

        verifyNoInteractions(orgAllowedDomainService);
        verify(workspaceMapper, never()).activateMember(7, 9);
    }

    @Test
    void approveMembershipRejectsAnUnversionedPendingGrant() {
        WorkspaceMember membership = membership(9, "member", null, "pending");
        MemberDto pending = member("Pending", "member", "pending");
        pending.setEmail("pending@example.com");
        when(userMapper.lockById(9)).thenReturn(9);
        stubActiveWorkspaceIdentity();
        when(workspaceMapper.lockAuthorizationMembership(7, 9)).thenReturn(membership);
        when(workspaceMapper.getMember(7, 9)).thenReturn(pending);
        when(orgAllowedDomainService.isJoinAllowed(3, "pending@example.com")).thenReturn(true);
        when(workspaceMapper.activateMember(7, 9)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> service.approveMembership(7, 9));

        verify(stateVersionService, never()).markChanged(9);
        verifyNoInteractions(auditService);
    }

    @Test
    void approveMembershipFailsClosedWhenLockedPendingMemberCannotBeLoaded() {
        when(userMapper.lockById(9)).thenReturn(9);
        stubActiveWorkspaceIdentity();
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, "member", null, "pending"));

        assertThrows(ResourceNotFoundException.class, () -> service.approveMembership(7, 9));

        verifyNoInteractions(orgAllowedDomainService);
        verify(workspaceMapper, never()).activateMember(7, 9);
    }

    @Test
    void approveMembershipRejectsAnInactiveOrganizationBeforeMembershipLock() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockActiveIdentity(7)).thenReturn(workspace(7, 3));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.approveMembership(7, 9));

        verify(workspaceMapper, never()).lockAuthorizationMembership(7, 9);
    }

    @Test
    void approveMembershipRejectsAReservedAccountBeforeWorkspaceLock() {
        when(userMapper.lockById(9)).thenReturn(9);
        when(userMapper.isAccountDeletionReserved(9)).thenReturn(true);

        assertThrows(ResourceNotFoundException.class, () -> service.approveMembership(7, 9));

        verify(workspaceMapper, never()).lockActiveIdentity(7);
    }

    private void stubOwnerActor() {
        when(workspaceMapper.getMemberRoleId(7, 1)).thenReturn(null);
        when(workspaceMapper.getRole(7, 1)).thenReturn("owner");
        when(userMapper.lockById(1)).thenReturn(1);
        when(workspaceMapper.lockAuthorizationMembership(7, 1))
            .thenReturn(membership(1, "owner", null, "active"));
    }

    private void stubRoleMutationTarget(String role) {
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspace(7)).thenReturn(7);
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership(9, role, null, "active"));
        when(workspaceMapper.getMember(7, 9)).thenReturn(member("Target", role, "active"));
    }

    private void stubActiveWorkspaceIdentity() {
        when(workspaceMapper.lockActiveIdentity(7)).thenReturn(workspace(7, 3));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
    }

    private static WorkspaceMember membership(
            int userId, String role, Integer roleId, String status) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(7);
        membership.setUserId(userId);
        membership.setRole(role);
        membership.setRoleId(roleId);
        membership.setStatus(status);
        return membership;
    }

    private static Workspace workspace(int workspaceId, int orgId) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setOrgId(orgId);
        return workspace;
    }

    private static MemberDto member(String displayName, String role, String status) {
        MemberDto member = new MemberDto();
        member.setDisplayName(displayName);
        member.setRole(role);
        member.setStatus(status);
        return member;
    }
}
