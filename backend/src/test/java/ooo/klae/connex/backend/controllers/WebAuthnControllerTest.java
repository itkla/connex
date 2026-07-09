package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyRegistrationOptionsRequest;
import ooo.klae.connex.backend.dto.RenamePasskeyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.SessionSecurityService;
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
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final WebAuthnController controller = new WebAuthnController(
        webAuthnService,
        authService,
        json,
        creationOptions,
        requestOptions,
        loginRateLimiter,
        clientIpResolver,
        ssoConnectionService,
        sessionSecurityService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registerVerify_preservesRequestBodyTooLarge() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(creationOptions.load(request)).thenReturn(mock(PublicKeyCredentialCreationOptions.class));
        when(authService.getCurrentUser()).thenReturn(user(7));
        when(webAuthnService.hasPasskey(7)).thenReturn(true);

        assertThrows(RequestBodyTooLargeException.class,
            () -> controller.registerVerify("work key", "{}", request, response));
    }

    @Test
    void registerOptions_firstPasskeyRequiresCurrentPasswordBootstrap() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PasskeyRegistrationOptionsRequest body = new PasskeyRegistrationOptionsRequest();
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController registrationController = controller(mapper);
        body.setCurrentPassword("Str0ng!Pass");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(webAuthnService.createRegistrationOptions(any())).thenReturn(options);
        when(mapper.write(options)).thenReturn("{}");

        registrationController.registerOptions(body, request, response);

        verify(authService).requireCurrentPassword(7, "Str0ng!Pass", "127.0.0.1");
        verify(sessionSecurityService, never()).requireRecentAuthentication(7);
        assertEquals(7, request.getSession().getAttribute("connex.firstPasskeyBootstrapUserId"));
    }

    @Test
    void registerOptions_existingPasskeyRequiresWebAuthnStepUp() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController registrationController = controller(mapper);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(webAuthnService.createRegistrationOptions(any())).thenReturn(options);
        when(mapper.write(options)).thenReturn("{}");

        registrationController.registerOptions(null, request, response);

        verify(sessionSecurityService).requireRecentAuthentication(7);
        verify(authService, never()).requireCurrentPassword(anyInt(), any(), any());
    }

    @Test
    void registerVerify_firstPasskeyRequiresBootstrapProof() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(creationOptions.load(request)).thenReturn(mock(PublicKeyCredentialCreationOptions.class));
        when(authService.getCurrentUser()).thenReturn(user(7));
        when(webAuthnService.hasPasskey(7)).thenReturn(false);

        assertThrows(BadRequestException.class,
            () -> controller.registerVerify("work key", "{}", request, response));

        verify(webAuthnService, never()).finishRegistration(any(), any(), any());
    }

    @Test
    void registerVerify_existingPasskeyRequiresWebAuthnStepUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(creationOptions.load(request)).thenReturn(mock(PublicKeyCredentialCreationOptions.class));
        when(authService.getCurrentUser()).thenReturn(user(7));
        when(webAuthnService.hasPasskey(7)).thenReturn(true);

        assertThrows(RequestBodyTooLargeException.class,
            () -> controller.registerVerify("work key", "{}", request, response));

        verify(sessionSecurityService).requireRecentAuthentication(7);
    }

    @Test
    void renameCredential_requiresRecentWebAuthnStepUp() {
        User user = user(7);
        RenamePasskeyRequest request = new RenamePasskeyRequest();
        request.setLabel("Work key");
        when(authService.getCurrentUser()).thenReturn(user);

        controller.renameCredential("credential-id", request);

        verify(sessionSecurityService).requireRecentAuthentication(7);
        verify(webAuthnService).rename(7, "credential-id", "Work key");
    }

    @Test
    void deleteCredential_requiresRecentWebAuthnStepUp() {
        User user = user(7);
        when(authService.getCurrentUser()).thenReturn(user);

        controller.deleteCredential("credential-id");

        verify(sessionSecurityService).requireRecentAuthentication(7);
        verify(webAuthnService).delete(7, "credential-id");
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

    @Test
    void stepUpVerify_requiresPendingChallenge() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(BadCredentialsException.class,
            () -> controller.stepUpVerify("{}", request, response));

        verify(sessionSecurityService, never()).markStepUp(any(), anyInt());
    }

    @Test
    void stepUpVerify_marksSessionAfterCurrentUserAssertion() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController stepUpController = controller(mapper);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenReturn(assertion);
        when(authService.getCurrentUser()).thenReturn(user);

        Map<String, String> result = stepUpController.stepUpVerify("{}", request, response);

        verify(webAuthnService).finishStepUp(any(), eq(options), eq(assertion));
        verify(sessionSecurityService).markStepUp(request, 7);
        assertEquals("Recent authentication refreshed", result.get("message"));
    }

    @Test
    void passkeyLoginMarksWebAuthnStepUp() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController loginController = controller(mapper);
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenReturn(assertion);
        when(webAuthnService.finishLogin(options, assertion)).thenReturn(user);
        when(ssoConnectionService.isSsoEnforcedForUser(7)).thenReturn(false);

        loginController.authenticateVerify("{}", request, response);

        verify(authService).establishAuthenticatedSession(user, request, response);
        verify(sessionSecurityService).markStepUp(request, 7);
    }

    private WebAuthnController controller(WebAuthnJsonMapper mapper) {
        return new WebAuthnController(
            webAuthnService,
            authService,
            mapper,
            creationOptions,
            requestOptions,
            loginRateLimiter,
            clientIpResolver,
            ssoConnectionService,
            sessionSecurityService);
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
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
