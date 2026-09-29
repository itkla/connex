package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.mappers.SsoConnectionMapper;

class DbRelyingPartyRegistrationRepositoryUnitTest {
    private final SsoConnectionMapper ssoConnectionMapper = mock(SsoConnectionMapper.class);
    private final SsoSecretCipher ssoSecretCipher = mock(SsoSecretCipher.class);
    private final DbRelyingPartyRegistrationRepository repository =
        new DbRelyingPartyRegistrationRepository(ssoConnectionMapper, ssoSecretCipher);

    @Test
    void malformedRegistrationId_resolvesNull() {
        assertNull(repository.findByRegistrationId("not-an-org"));
        assertNull(repository.findByRegistrationId("org-abc"));
        assertNull(repository.findByRegistrationId(null));
        verifyNoInteractions(ssoConnectionMapper, ssoSecretCipher);
    }
}
