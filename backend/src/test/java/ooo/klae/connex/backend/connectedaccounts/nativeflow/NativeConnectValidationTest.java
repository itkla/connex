package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenClient;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCaptureConnectionStateService;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class NativeConnectValidationTest {
    private final NativeConnectSessionPersistence sessionPersistence = mock(NativeConnectSessionPersistence.class);
    private final TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
    private final NativeConnectService nativeConnectService = new NativeConnectService(
        mock(ConnectedAccountProviders.class),
        sessionPersistence,
        mock(NativeConnectPkceSecretCipher.class),
        mock(ProviderTokenClient.class),
        mock(ProviderAccountIdentityResolver.class),
        mock(ProviderCaptureConnectionStateService.class),
        mock(WorkspaceService.class),
        mock(SessionSecurityService.class),
        mock(AuditService.class),
        mock(MailProperties.class),
        tenantWorkScope,
        mock(UserMapper.class),
        Clock.systemUTC());

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:49152/callback",
        "https://127.0.0.1:49152/callback",
        "http://127.0.0.1:80/callback",
        "http://evil.example.com/callback",
        "http://127.0.0.1:49152/callback?x=1",
        "http://127.0.0.1:49152/other",
        "http://user@127.0.0.1:49152/callback"
    })
    void nonExactLoopbackRedirectUrisAreRejected(String redirectUri) {
        String pairingCode = NativeConnectPkce.randomSecret();

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.prepare(
                new NativePrepareRequest(pairingCode, redirectUri)));

        assertEquals("invalid_redirect_uri", error.getCode());
        verifyNoInteractions(sessionPersistence, tenantWorkScope);
    }
}
