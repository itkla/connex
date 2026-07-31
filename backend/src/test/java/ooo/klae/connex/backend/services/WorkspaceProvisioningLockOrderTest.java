package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class WorkspaceProvisioningLockOrderTest {
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
    void provisionWorkspaceRequiresOwnerRootBeforeResolvingOrganization() {
        when(userMapper.lockByIdForShare(9)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> workspaceService.provisionWorkspace("Acme", 9));

        verifyNoInteractions(organizationMapper, workspaceMapper, orgMemberService, auditService);
    }

    @Test
    void newWorkspaceProvisioningDoesNotRunExistingWorkspaceCleanup() {
        Organization organization = new Organization();
        organization.setId(3);
        organization.setName("Acme");
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        doAnswer(invocation -> {
            invocation.getArgument(0, Organization.class).setId(3);
            return 1;
        }).when(organizationMapper).insert(org.mockito.ArgumentMatchers.any());
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);
        when(organizationMapper.getById(3)).thenReturn(organization);
        when(orgMemberService.orgRoleOf(3, 9)).thenReturn("owner");
        doAnswer(invocation -> {
            invocation.getArgument(0, Workspace.class).setId(7);
            return 1;
        }).when(workspaceMapper).insert(org.mockito.ArgumentMatchers.any());

        WorkspaceMembershipDto membership =
            workspaceService.provisionWorkspace("Acme", 9);

        assertEquals(7, membership.getId());
        assertEquals(3, membership.getOrgId());
        verify(workspaceMapper).addMember(7, 9, "owner");
        verify(userOffboardingService, never())
            .prepareFreshMembership(7, 9);
    }
}
