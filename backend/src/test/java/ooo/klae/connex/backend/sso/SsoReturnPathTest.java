package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.SocialLoginService;
import ooo.klae.connex.backend.services.SsoLinkService;
import ooo.klae.connex.backend.services.SsoLoginResult;
import ooo.klae.connex.backend.services.SsoLoginService;

/** Exercises invitation navigation across enterprise, social, session rotation and refusal paths. */
@ExtendWith(MockitoExtension.class)
class SsoReturnPathTest {
    @Mock private SsoLoginService ssoLoginService;
    @Mock private SocialLoginService socialLoginService;
    @Mock private SsoLinkService ssoLinkService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private MailProperties mailProperties;
    @Mock private SsoConnectionMapper ssoConnectionMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OneTimeLinkFlowService oneTimeLinkFlowService;
    @Mock private OneTimeLinkFlowCookie oneTimeLinkFlowCookie;
    @Mock private OidcUser oidcUser;
    @InjectMocks private SsoAuthenticationSuccessHandler handler;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"org-7", "google"})
    void successfulEnterpriseOrSocialLoginReturnsToTheInvitationAfterSessionReplacement(String provider) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/oauth2/authorization/" + provider);
        matchedOAuth(request, provider, "/invite-link");
        MockHttpServletResponse response = new MockHttpServletResponse();
        User account = new User();
        account.setId(19);
        account.setDisplayName("Member");
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(oidcUser.getEmail()).thenReturn("member@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getFullName()).thenReturn("Member");
        if ("google".equals(provider)) {
            when(oidcUser.getIssuer()).thenReturn(URI.create("https://issuer.example").toURL());
            when(oidcUser.getSubject()).thenReturn("subject");
            when(socialLoginService.resolve("google", "https://issuer.example", "subject", "member@example.com", true, "Member"))
                .thenReturn(new SsoLoginResult.Login(account));
        } else {
            Instant now = Instant.now();
            when(oidcUser.getIdToken()).thenReturn(new OidcIdToken("id-token", now, now.plusSeconds(60),
                    Map.of("iss", "https://issuer.example", "sub", "subject", "aud", List.of("client"))));
            when(ssoLoginService.resolve("oidc", "https://issuer.example", "subject", "member@example.com", true, 7, "Member", "client"))
                .thenReturn(new SsoLoginResult.Login(account));
        }
        when(authService.establishAuthenticatedSession(account, request, response)).thenAnswer(invocation -> {
            request.getSession().invalidate();
            request.getSession(true);
            return account;
        });

        handler.onAuthenticationSuccess(request, response, new OAuth2AuthenticationToken(oidcUser, List.of(), provider));

        assertEquals("https://app.example/invite-link", response.getRedirectedUrl());
        verifyNoInteractions(workspaceMapper);
    }

    @Test
    void successfulSamlLoginReturnsToTheValidatedInvitation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/saml2/authenticate/org-7");
        matchedSaml(request, "/invite");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = mock(org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication.class);
        var assertion = mock(org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor.class);
        when(authentication.getCredentials()).thenReturn(assertion);
        when(authentication.getRelyingPartyRegistrationId()).thenReturn("org-7");
        when(assertion.getAttribute("email")).thenReturn(List.of("member@example.com"));
        when(assertion.getAttribute("displayName")).thenReturn(List.of("Member"));
        when(assertion.getNameId()).thenReturn("subject");
        var connection = new ooo.klae.connex.backend.beans.SsoConnection();
        connection.setSamlIdpEntityId("https://idp.example");
        when(ssoConnectionMapper.findByOrg(7)).thenReturn(connection);
        User account = new User();
        account.setId(19);
        account.setDisplayName("Member");
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(ssoLoginService.resolve("saml", "https://idp.example", "subject", "member@example.com", true, 7, "Member", null))
            .thenReturn(new SsoLoginResult.Login(account));
        when(authService.establishAuthenticatedSession(account, request, response)).thenReturn(account);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("https://app.example/invite", response.getRedirectedUrl());
        verifyNoInteractions(workspaceMapper);
    }

    @Test
    void failureRetainsOnlyTheValidatedLocalDestination() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        matchedOAuth(request, "google", "/invite");

        assertEquals("/auth/login?sso_error=1&redirect=%2Finvite", AuthenticationReturnPathSession.failurePath(request));
    }
    @Test
    void interleavedCrossSiteDisabledSamlStartCannotChangeTheGoogleCallbackLandingPage() throws Exception {
        var registrations = mock(CompositeClientRegistrationRepository.class);
        var samlRegistrations = mock(org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository.class);
        when(registrations.findByRegistrationId("google")).thenReturn(mock(org.springframework.security.oauth2.client.registration.ClientRegistration.class));
        var filter = new AuthenticationReturnPathFilter(false, registrations, samlRegistrations);
        var repository = new OAuthAuthenticationAttemptRepository();
        var start = new MockHttpServletRequest("GET", "/api/oauth2/authorization/google");
        start.addParameter("redirect", "/invite");
        start.addHeader("Sec-Fetch-Site", "same-origin");
        var authorization = SsoAttemptRepositoryTest.oauth("google", "original-state");
        filter.doFilter(start, new MockHttpServletResponse(), (incoming, outgoing) ->
                repository.saveAuthorizationRequest(authorization, start, new MockHttpServletResponse()));
        var injected = new MockHttpServletRequest("GET", "/saml2/authenticate/org-0");
        injected.setSession(start.getSession(false));
        injected.addHeader("Sec-Fetch-Site", "cross-site");
        injected.addParameter("redirect", "/settings/personal/security");
        var refused = new MockHttpServletResponse();
        filter.doFilter(injected, refused, (incoming, outgoing) -> {
            throw new AssertionError("Disabled SAML cannot begin");
        });
        assertEquals(403, refused.getStatus());
        var callback = new MockHttpServletRequest("GET", "/api/login/oauth2/code/google");
        callback.setSession(start.getSession(false));
        callback.addParameter("state", authorization.getState());
        assertEquals(authorization, repository.removeAuthorizationRequest(callback, new MockHttpServletResponse()));
        User account = new User();
        account.setId(19);
        account.setDisplayName("Member");
        var response = new MockHttpServletResponse();
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(oidcUser.getEmail()).thenReturn("member@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getFullName()).thenReturn("Member");
        when(oidcUser.getIssuer()).thenReturn(URI.create("https://issuer.example").toURL());
        when(oidcUser.getSubject()).thenReturn("subject");
        when(socialLoginService.resolve("google", "https://issuer.example", "subject", "member@example.com", true, "Member"))
                .thenReturn(new SsoLoginResult.Login(account));
        when(authService.establishAuthenticatedSession(account, callback, response)).thenReturn(account);

        handler.onAuthenticationSuccess(callback, response, new OAuth2AuthenticationToken(oidcUser, List.of(), "google"));

        assertEquals("https://app.example/invite", response.getRedirectedUrl());
        verifyNoInteractions(samlRegistrations, workspaceMapper);
    }

    static void matchedOAuth(MockHttpServletRequest request, String provider, String destination) {
        request.addParameter("redirect", destination);
        AuthenticationReturnPathSession.prepareStart(request);
        var authorization = org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.example/authorize").clientId("client")
                .redirectUri("https://app.example/api/login/oauth2/code/" + provider)
                .state("handler-state").attributes(Map.of("registration_id", provider)).build();
        var repository = new OAuthAuthenticationAttemptRepository();
        repository.saveAuthorizationRequest(authorization, request, new MockHttpServletResponse());
        request.setRequestURI("/api/login/oauth2/code/" + provider);
        request.addParameter("state", authorization.getState());
        repository.removeAuthorizationRequest(request, new MockHttpServletResponse());
    }

    private static void matchedSaml(MockHttpServletRequest request, String destination) {
        request.addParameter("redirect", destination);
        AuthenticationReturnPathSession.prepareStart(request);
        var authorization = SsoAttemptRepositoryTest.saml("org-7", "saml-request-id", "saml-relay-state");
        var repository = new SamlAuthenticationAttemptRepository();
        repository.saveAuthenticationRequest(authorization, request, new MockHttpServletResponse());
        request.setRequestURI("/api/login/saml2/sso/org-7");
        request.addParameter("RelayState", authorization.getRelayState());
        repository.removeAuthenticationRequest(request, new MockHttpServletResponse());
    }
}
