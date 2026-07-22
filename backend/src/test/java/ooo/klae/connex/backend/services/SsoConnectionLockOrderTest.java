package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.SsoDomainMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SamlSpCredentialFactory;
import ooo.klae.connex.backend.sso.SsoProperties;
import ooo.klae.connex.backend.sso.SsoSecretCipher;

@ExtendWith(MockitoExtension.class)
class SsoConnectionLockOrderTest {
    @Mock private SsoConnectionMapper ssoConnectionMapper;
    @Mock private SsoDomainMapper ssoDomainMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private UserMapper userMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private SsoSecretCipher ssoSecretCipher;
    @Mock private SamlSpCredentialFactory samlSpCredentialFactory;
    @Mock private SsoProperties ssoProperties;
    @Mock private AuditService auditService;
    @Mock private DbClientRegistrationRepository dbClientRegistrationRepository;
    @Mock private DbRelyingPartyRegistrationRepository dbRelyingPartyRegistrationRepository;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private SsoConnectionService ssoConnectionService;

    @Test
    void saveRequiresActorRootBeforeResolvingOrganization() {
        when(userMapper.lockByIdForShare(9)).thenReturn(null);

        assertThrows(ForbiddenException.class,
                () -> ssoConnectionService.save(3, 9, new SsoConnectionRequest()));

        verifyNoInteractions(workspaceMapper, organizationMapper, orgMemberService, ssoConnectionMapper,
                ssoSecretCipher, auditService);
    }

    @Test
    void saveLocksCurrentAuthorizationBeforeReadingRequestOrWritingConfig() {
        SsoConnectionRequest request = new SsoConnectionRequest();
        request.setJitWorkspaceId(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspaceForShare(3)).thenReturn(3);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.getOrgId(5)).thenReturn(7);
        when(workspaceMapper.getOrgId(3)).thenReturn(7);
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(ssoProperties.isEnabled()).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> ssoConnectionService.save(5, 9, request));

        InOrder order = inOrder(userMapper, workspaceMapper, organizationMapper, orgMemberService);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(3);
        order.verify(workspaceMapper).lockWorkspaceForShare(5);
        order.verify(workspaceMapper).getOrgId(5);
        order.verify(workspaceMapper).getOrgId(3);
        order.verify(organizationMapper).lockById(7);
        order.verify(orgMemberService).requireOrgAdminForUpdate(7, 9);
        verifyNoInteractions(ssoConnectionMapper, ssoSecretCipher, auditService);
    }

    @Test
    void saveInsertsNormalizedDomainsInGlobalOrder() {
        SsoConnectionRequest request = new SsoConnectionRequest();
        request.setProtocol("oidc");
        request.setJitWorkspaceId(3);
        request.setDomains(List.of("b.example", "A.example"));
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspaceForShare(3)).thenReturn(3);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.getOrgId(5)).thenReturn(7);
        when(workspaceMapper.getOrgId(3)).thenReturn(7);
        when(organizationMapper.lockById(7)).thenReturn(7);
        when(ssoProperties.isEnabled()).thenReturn(true);
        when(ssoDomainMapper.listByOrg(7)).thenReturn(List.of());
        when(ssoDomainMapper.findOrgByDomain("a.example")).thenReturn(null);
        when(ssoDomainMapper.findOrgByDomain("b.example")).thenReturn(null);

        assertDoesNotThrow(() -> ssoConnectionService.save(5, 9, request));

        InOrder order = inOrder(ssoDomainMapper);
        order.verify(ssoDomainMapper).listByOrg(7);
        order.verify(ssoDomainMapper).findOrgByDomain("a.example");
        order.verify(ssoDomainMapper).insert("a.example", 7);
        order.verify(ssoDomainMapper).findOrgByDomain("b.example");
        order.verify(ssoDomainMapper).insert("b.example", 7);
    }
}
