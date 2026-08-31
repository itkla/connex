package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyRegistrationOptionsRequest;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.dto.RenamePasskeyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.LastPasskeyRemovalForbiddenException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.MfaRecoveryService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.SsoConnectionService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
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
    private final AuditService auditService = mock(AuditService.class);
    private final MfaRecoveryService mfaRecoveryService = mock(MfaRecoveryService.class);
    private final WebAuthnController controller = new WebAuthnController(
        webAuthnService,
        authService,
        json,
        creationOptions,
        requestOptions,
        loginRateLimiter,
        clientIpResolver,
        ssoConnectionService,
        sessionSecurityService,
        auditService,
        mfaRecoveryService);

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
        when(webAuthnService.createRegistrationOptions(any())).thenReturn(options);
        when(mapper.write(options)).thenReturn("{}");

        registrationController.registerOptions(body, request, response);

        verify(authService).requireFirstPasskeyBootstrapAuthentication(7, "Str0ng!Pass", request);
        verify(sessionSecurityService, never()).requireRecentAuthentication(7);
        verify(sessionSecurityService).markFirstPasskeyBootstrap(request, 7);
    }

    @Test
    void registerOptions_failedBootstrapProofDoesNotCreateChallengeOrMarker() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PasskeyRegistrationOptionsRequest body = new PasskeyRegistrationOptionsRequest();
        body.setCurrentPassword("wrong");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        doThrow(new BadCredentialsException("Incorrect password"))
            .when(authService).requireFirstPasskeyBootstrapAuthentication(7, "wrong", request);

        assertThrows(BadCredentialsException.class,
            () -> controller.registerOptions(body, request, response));

        verify(webAuthnService, never()).createRegistrationOptions(any());
        verify(sessionSecurityService, never()).markFirstPasskeyBootstrap(any(), anyInt());
        verify(sessionSecurityService).clearFirstPasskeyBootstrap(request);
    }

    @Test
    void registrationRequirements_firstPasswordBackedPasskeyRequiresCurrentPassword() {
        User user = user(7);
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(authService.hasPasswordCredential(7)).thenReturn(true);

        assertTrue(controller.registrationRequirements().currentPasswordRequired());
    }

    @Test
    void registrationRequirements_passwordlessAccountDoesNotRequireCurrentPassword() {
        User user = user(7);
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(false);
        when(authService.hasPasswordCredential(7)).thenReturn(false);

        assertFalse(controller.registrationRequirements().currentPasswordRequired());
    }

    @Test
    void registrationRequirements_existingPasskeyDoesNotRequireCurrentPassword() {
        User user = user(7);
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(true);

        assertFalse(controller.registrationRequirements().currentPasswordRequired());
        verify(authService, never()).hasPasswordCredential(anyInt());
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
        verify(authService, never()).requireFirstPasskeyBootstrapAuthentication(anyInt(), any(), any());
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

        verify(webAuthnService, never()).finishRegistration(anyInt(), any(), any(), any(), any());
    }

    @Test
    void registerVerifyPassesTheRequestSessionsEpochFence() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialCreationOptions options = mock(PublicKeyCredentialCreationOptions.class);
        PublicKeyCredential<AuthenticatorAttestationResponse> credential = mock();
        CredentialRecord record = mock(CredentialRecord.class);
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController registrationController = controller(mapper);
        when(creationOptions.load(request)).thenReturn(options);
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers
                .<TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>>>any()))
                .thenReturn(credential);
        when(sessionSecurityService.sessionEpoch(request.getSession(false))).thenReturn(6);
        when(webAuthnService.finishRegistration(7, 6, options, credential, "work key"))
                .thenReturn(record);
        when(record.getCredentialId()).thenReturn(Bytes.random());

        registrationController.registerVerify("work key", "{}", request, response);

        verify(sessionSecurityService).sessionEpoch(request.getSession(false));
        verify(webAuthnService).finishRegistration(7, 6, options, credential, "work key");
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
    void deleteCredentialAuditsRefusalToRemovePrivilegedLastCredential() {
        User user = user(7);
        when(authService.getCurrentUser()).thenReturn(user);
        org.mockito.Mockito.doThrow(new LastPasskeyRemovalForbiddenException())
                .when(webAuthnService).delete(7, "credential-id");

        assertThrows(LastPasskeyRemovalForbiddenException.class,
                () -> controller.deleteCredential("credential-id"));

        verify(auditService).recordFailureScoped(
                eq("auth.passkey.delete_denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("User 7"), eq("Last passkey removal refused for privileged account"),
                eq("last_credential"));
    }

    @Test
    void authenticateVerify_preservesRequestBodyTooLargeWithoutRecordingLoginFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResolvedClientIp clientIp = new ResolvedClientIp("127.0.0.1", false);
        when(clientIpResolver.resolveWithProvenance(request)).thenReturn(clientIp);
        when(requestOptions.load(request)).thenReturn(mock(PublicKeyCredentialRequestOptions.class));

        assertThrows(RequestBodyTooLargeException.class,
            () -> controller.authenticateVerify("{}", request, response));

        verify(loginRateLimiter, never()).recordFailureForClient(any(), any(), anyLong());
    }

    @Test
    void authenticateVerifyAuditsFailedPasskeyAttempt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController loginController = controller(mapper);
        ResolvedClientIp clientIp = new ResolvedClientIp("127.0.0.1", false);
        when(clientIpResolver.resolveWithProvenance(request)).thenReturn(clientIp);
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenThrow(new BadCredentialsException("bad assertion"));

        assertThrows(BadCredentialsException.class, () -> loginController.authenticateVerify("{}", request, response));

        verify(loginRateLimiter).recordFailureForClient(eq(clientIp), isNull(), anyLong());
        verify(auditService).recordFailure(eq("auth.login.passkey"), eq("user"), isNull(), eq("127.0.0.1"),
            eq("Failed passkey login attempt"), eq("bad assertion"));
    }

    @Test
    void authenticateVerifyAuditsMissingPasskeyChallenge() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(clientIpResolver.resolveWithProvenance(request))
            .thenReturn(new ResolvedClientIp("127.0.0.1", false));

        assertThrows(BadCredentialsException.class, () -> controller.authenticateVerify("{}", request, response));

        verify(auditService).recordFailure(eq("auth.login.passkey"), eq("user"), isNull(), eq("127.0.0.1"),
            eq("Passkey login missing challenge"), isNull());
    }

    @Test
    void authenticateVerifyAuditsSsoEnforcedPasskeyRefusal() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController loginController = controller(mapper);
        when(clientIpResolver.resolveWithProvenance(request))
            .thenReturn(new ResolvedClientIp("127.0.0.1", false));
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenReturn(assertion);
        when(webAuthnService.finishLogin(options, assertion)).thenReturn(user);
        when(ssoConnectionService.isSsoEnforcedForUser(7)).thenReturn(true);

        assertThrows(ooo.klae.connex.backend.exceptions.ForbiddenException.class,
            () -> loginController.authenticateVerify("{}", request, response));

        verify(auditService).recordFailure(eq("auth.login.passkey_sso_enforced"), eq("user"), eq(7),
            eq("User 7"), eq("Passkey login refused; SSO enforced"), isNull());
    }

    @Test
    void stepUpVerify_requiresPendingChallenge() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authService.getCurrentUser()).thenReturn(user);

        assertThrows(BadCredentialsException.class,
            () -> controller.stepUpVerify("{}", request, response));

        verify(sessionSecurityService, never()).markStepUp(any(), anyInt());
        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.step_up.passkey"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("Failed passkey step-up attempt"), eq("missing_challenge"));
    }

    @Test
    void stepUpVerifyAuditsFailedAssertion() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController stepUpController = controller(mapper);
        when(authService.getCurrentUser()).thenReturn(user);
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenThrow(new BadCredentialsException("bad step-up"));

        assertThrows(BadCredentialsException.class, () -> stepUpController.stepUpVerify("{}", request, response));

        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.step_up.passkey"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("Failed passkey step-up attempt"), eq("verification_failed"));
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
        InOrder grantOrder = inOrder(auditService, sessionSecurityService);
        grantOrder.verify(auditService).recordStrictIndependentScoped(
                eq("auth.step_up.passkey"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("Passkey step-up completed"), isNull());
        grantOrder.verify(sessionSecurityService).markStepUp(request, 7);
        assertEquals("Recent authentication refreshed", result.get("message"));
    }

    @Test
    void recoveryDenialIsDurablyAuditedWithoutProofMaterial() {
        User user = user(7);
        PasskeyRecoveryRequest recovery = new PasskeyRecoveryRequest();
        recovery.setCurrentPassword("sensitive-current-password");
        recovery.setRecoveryToken("sensitive-operator-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authService.getCurrentUser()).thenReturn(user);
        doThrow(new ForbiddenException("MFA recovery authorization is invalid or expired"))
                .when(mfaRecoveryService).recover(recovery, request);

        assertThrows(ForbiddenException.class,
                () -> controller.recoverCredentials(recovery, request));

        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.mfa.recovery.denied"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("Passkey recovery denied"), eq("proof_rejected"));
        assertFalse(recovery.toString().contains("sensitive-current-password"));
        assertFalse(recovery.toString().contains("sensitive-operator-token"));
    }

    @Test
    void recoveryStampsTheCeremonySessionAfterTheTransactionalServiceReturns() {
        User user = user(7);
        PasskeyRecoveryRequest recovery = new PasskeyRecoveryRequest();
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authService.getCurrentUser()).thenReturn(user);
        when(mfaRecoveryService.recover(recovery, request)).thenReturn(5);

        controller.recoverCredentials(recovery, request);

        InOrder completionOrder = inOrder(mfaRecoveryService, sessionSecurityService);
        completionOrder.verify(mfaRecoveryService).recover(recovery, request);
        completionOrder.verify(sessionSecurityService).completeRecoveryStamp(request, 5);
    }

    @Test
    void stepUpAuditFailureCannotGrantRecentAuthentication() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController stepUpController = controller(mapper);
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
                .thenReturn(assertion);
        when(authService.getCurrentUser()).thenReturn(user);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).recordStrictIndependentScoped(
                        eq("auth.step_up.passkey"), eq("user"), eq(7), isNull(), isNull(),
                        eq("User 7"), eq("Passkey step-up completed"), isNull());

        assertThrows(IllegalStateException.class,
                () -> stepUpController.stepUpVerify("{}", request, response));

        verify(sessionSecurityService, never()).markStepUp(any(), anyInt());
    }

    @Test
    void blankRecoveryProofDenialIsDurablyAudited() {
        User user = user(7);
        PasskeyRecoveryRequest recovery = new PasskeyRecoveryRequest();
        recovery.setCurrentPassword("current-password");
        recovery.setRecoveryToken(" ");
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(authService.getCurrentUser()).thenReturn(user);
        doThrow(new ForbiddenException("MFA recovery authorization is invalid or expired"))
                .when(mfaRecoveryService).recover(recovery, request);

        assertThrows(ForbiddenException.class,
                () -> controller.recoverCredentials(recovery, request));

        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.mfa.recovery.denied"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("Passkey recovery denied"), eq("proof_rejected"));
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
        when(clientIpResolver.resolveWithProvenance(request))
            .thenReturn(new ResolvedClientIp("127.0.0.1", false));
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
            .thenReturn(assertion);
        when(webAuthnService.finishLogin(options, assertion)).thenReturn(user);
        when(ssoConnectionService.isSsoEnforcedForUser(7)).thenReturn(false);
        when(authService.establishAuthenticatedSession(user, request, response)).thenReturn(user);

        loginController.authenticateVerify("{}", request, response);

        InOrder grantOrder = inOrder(auditService, authService, sessionSecurityService);
        grantOrder.verify(auditService).recordStrictIndependentScoped(
                eq("auth.login.passkey"), eq("user"), eq(7), isNull(), isNull(), eq("User 7"),
                eq("User 7 logged in with passkey"), isNull());
        grantOrder.verify(authService).establishAuthenticatedSession(user, request, response);
        grantOrder.verify(sessionSecurityService).markStepUp(request, 7);
    }

    @Test
    void passkeyLoginAuditFailureCannotGrantWebAuthnStepUp() {
        User user = user(7);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
        WebAuthnJsonMapper mapper = mock(WebAuthnJsonMapper.class);
        WebAuthnController loginController = controller(mapper);
        when(clientIpResolver.resolveWithProvenance(request))
                .thenReturn(new ResolvedClientIp("127.0.0.1", false));
        when(requestOptions.load(request)).thenReturn(options);
        when(mapper.read(eq("{}"), org.mockito.ArgumentMatchers.<TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>>>any()))
                .thenReturn(assertion);
        when(webAuthnService.finishLogin(options, assertion)).thenReturn(user);
        when(ssoConnectionService.isSsoEnforcedForUser(7)).thenReturn(false);
        when(authService.establishAuthenticatedSession(user, request, response)).thenReturn(user);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).recordStrictIndependentScoped(
                        eq("auth.login.passkey"), eq("user"), eq(7), isNull(), isNull(),
                        eq("User 7"), eq("User 7 logged in with passkey"), isNull());

        assertThrows(IllegalStateException.class,
                () -> loginController.authenticateVerify("{}", request, response));

        verify(authService, never()).establishAuthenticatedSession(any(), any(), any());
        verify(sessionSecurityService, never()).markStepUp(any(), anyInt());
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
            sessionSecurityService,
            auditService,
            mfaRecoveryService);
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setDisplayName("User " + id);
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
