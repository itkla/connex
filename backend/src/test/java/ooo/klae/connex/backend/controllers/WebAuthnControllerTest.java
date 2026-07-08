package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnJsonMapper;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import tools.jackson.core.type.TypeReference;

class WebAuthnControllerTest {
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final AuthService authService = mock(AuthService.class);
    private final WebAuthnJsonMapper json = new TooLargeMapper();
    private final PublicKeyCredentialCreationOptionsRepository creationOptions =
        mock(PublicKeyCredentialCreationOptionsRepository.class);
    private final PublicKeyCredentialRequestOptionsRepository requestOptions =
        mock(PublicKeyCredentialRequestOptionsRepository.class);
    private final LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final SsoConnectionService ssoConnectionService = mock(SsoConnectionService.class);
    private final WebAuthnController controller = new WebAuthnController(
        webAuthnService,
        authService,
        json,
        creationOptions,
        requestOptions,
        loginRateLimiter,
        clientIpResolver,
        ssoConnectionService);

    @Test
    void registerVerify_preservesRequestBodyTooLarge() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(creationOptions.load(request)).thenReturn(mock(PublicKeyCredentialCreationOptions.class));

        assertThrows(RequestBodyTooLargeException.class,
            () -> controller.registerVerify("work key", "{}", request, response));
    }

    @Test
    void authenticateVerify_preservesRequestBodyTooLargeWithoutRecordingLoginFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(requestOptions.load(request)).thenReturn(mock(PublicKeyCredentialRequestOptions.class));

        assertThrows(RequestBodyTooLargeException.class,
            () -> controller.authenticateVerify("{}", request, response));

        verify(loginRateLimiter, never()).recordFailure(any(), any(), anyLong());
    }

    private static final class TooLargeMapper extends WebAuthnJsonMapper {
        TooLargeMapper() {
            super(properties());
        }

        @Override
        public <T> T read(String json, TypeReference<T> type) {
            throw new RequestBodyTooLargeException(4);
        }

        private static RequestBodySizeProperties properties() {
            RequestBodySizeProperties properties = new RequestBodySizeProperties();
            properties.setWebauthnMaxBodyBytes(4);
            return properties;
        }
    }
}
