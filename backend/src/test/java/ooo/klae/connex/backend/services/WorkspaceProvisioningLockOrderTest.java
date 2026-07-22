package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
