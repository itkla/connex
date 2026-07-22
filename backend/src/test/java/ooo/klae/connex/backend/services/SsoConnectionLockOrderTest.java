package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.SsoConnectionRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceService workspaceService;
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

        verifyNoInteractions(workspaceMapper, orgMemberService, ssoConnectionMapper, ssoSecretCipher, auditService);
    }
}
