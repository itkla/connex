package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgAllowedDomainService;
import ooo.klae.connex.backend.services.OrgMemberService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.services.UserOffboardingService;
import ooo.klae.connex.backend.services.WorkspaceMembershipRemovalTransaction;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Pins the root-before-child lock order of both credential management paths. Issuance and
 * revocation reach {@code api_credential} only through the locked authorization, so the workspace
 * root and its organization root must already be held shared before any credential row is locked.
 */
class ApiCredentialManagementLockOrderTest {
    private static final int WORKSPACE_ID = 7;
    private static final int ORGANIZATION_ID = 2;
    private static final int ACTOR_ID = 41;
    private static final long MEMBERSHIP_ID = 55L;

    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final ApiCredentialMapper apiCredentialMapper = mock(ApiCredentialMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final TenantContext tenantContext = mock(TenantContext.class);

    private ApiCredentialService service;

    @BeforeEach
    void setUp() {
        WorkspaceService workspaceService = new WorkspaceService(
            workspaceMapper,
            userMapper,
            organizationMapper,
            mock(OrgMemberService.class),
            mock(OrgAllowedDomainService.class),
            roleMapper,
            mock(NotificationMapper.class),
            mock(UserOffboardingService.class),
            mock(WorkspaceMembershipRemovalTransaction.class),
            mock(NotificationDelivery.class),
            mock(NotificationStateVersionService.class),
            tenantContext,
            auditService,
            mock(SystemActor.class),
            sessionSecurityService);
        service = new ApiCredentialService(
            apiCredentialMapper,
            userMapper,
            workspaceService,
            auditService,
            sessionSecurityService,
            true,
            20);
        User actor = new User();
        actor.setId(ACTOR_ID);
        actor.setUsername("credential-manager");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        when(tenantContext.isResolved()).thenReturn(true);
        when(tenantContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(userMapper.isAccountDeletionReservedForShare(ACTOR_ID)).thenReturn(Boolean.FALSE);
        when(workspaceMapper.lockActiveWorkspaceForShare(WORKSPACE_ID)).thenReturn(ORGANIZATION_ID);
        when(organizationMapper.lockByIdForShare(ORGANIZATION_ID)).thenReturn(ORGANIZATION_ID);
        when(workspaceMapper.lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(ownerMembership());
        when(workspaceMapper.getMembershipGenerationId(WORKSPACE_ID, ACTOR_ID))
            .thenReturn(MEMBERSHIP_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void issueTakesBothRootsSharedBeforeAnyCredentialRow() {
        ApiCredential persisted = credential();
        when(apiCredentialMapper.findByTokenHash(anyString())).thenReturn(persisted);

        service.issue("Ordering", Set.of(ApiScope.CRM_READ), LocalDateTime.now().plusDays(30));

        InOrder order =
            inOrder(userMapper, workspaceMapper, organizationMapper, apiCredentialMapper);
        order.verify(userMapper).isAccountDeletionReservedForShare(ACTOR_ID);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(WORKSPACE_ID);
        order.verify(organizationMapper).lockByIdForShare(ORGANIZATION_ID);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID);
        order.verify(apiCredentialMapper)
            .deleteInactiveByMembership(WORKSPACE_ID, ACTOR_ID, MEMBERSHIP_ID);
        order.verify(apiCredentialMapper).insert(any(ApiCredential.class));
    }

    @Test
    void revokeTakesBothRootsSharedBeforeTheCredentialRow() {
        ApiCredential persisted = credential();
        when(apiCredentialMapper.findByIdForUpdate(WORKSPACE_ID, persisted.getId()))
            .thenReturn(persisted);
        when(apiCredentialMapper.revoke(WORKSPACE_ID, persisted.getId(), ACTOR_ID)).thenReturn(1);

        service.revoke(persisted.getId());

        InOrder order =
            inOrder(userMapper, workspaceMapper, organizationMapper, apiCredentialMapper);
        order.verify(userMapper).isAccountDeletionReservedForShare(ACTOR_ID);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(WORKSPACE_ID);
        order.verify(organizationMapper).lockByIdForShare(ORGANIZATION_ID);
        order.verify(workspaceMapper).lockAuthorizationMembership(WORKSPACE_ID, ACTOR_ID);
        order.verify(apiCredentialMapper).findByIdForUpdate(WORKSPACE_ID, persisted.getId());
        order.verify(apiCredentialMapper).revoke(WORKSPACE_ID, persisted.getId(), ACTOR_ID);
    }

    @Test
    void aVanishedOrganizationRootRefusesManagementBeforeAnyCredentialRow() {
        when(organizationMapper.lockByIdForShare(ORGANIZATION_ID)).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.revoke(4242L));

        verify(apiCredentialMapper, never()).findByIdForUpdate(anyInt(), anyLong());
        verify(workspaceMapper, never()).lockAuthorizationMembership(anyInt(), anyInt());
    }

    @Test
    void recordingSuccessfulUseAcquiresNothingAfterItsCredentialRow() {
        when(apiCredentialMapper.updateLastUsed(4711L, "hash")).thenReturn(1);

        service.recordSuccessfulUse(4711L, "hash");

        verify(apiCredentialMapper).updateLastUsed(4711L, "hash");
        verifyNoMoreInteractions(apiCredentialMapper);
        verifyNoInteractions(workspaceMapper, organizationMapper, roleMapper, auditService);
    }

    private static WorkspaceMember ownerMembership() {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setWorkspaceId(WORKSPACE_ID);
        membership.setUserId(ACTOR_ID);
        membership.setRole("owner");
        membership.setStatus("active");
        return membership;
    }

    private static ApiCredential credential() {
        ApiCredential credential = new ApiCredential();
        credential.setId(4711L);
        credential.setWorkspaceId(WORKSPACE_ID);
        credential.setOrganizationId(ORGANIZATION_ID);
        credential.setCreatedById(ACTOR_ID);
        credential.setMembershipId(MEMBERSHIP_ID);
        credential.setName("Ordering");
        credential.setTokenLast4("ab12");
        credential.setExpiresAt(LocalDateTime.now().plusDays(30));
        return credential;
    }
}
