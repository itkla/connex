package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @ValueSource(strings = {"/api/oauth2/authorization/org-7", "/api/oauth2/authorization/google", "/saml2/authenticate/org-7",
            "/api/%6fauth2/authorization/google", "/saml2/authenticate;source=invite/org-7"})
    void capturesOnlyTheValidatedStartDestination(String start) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", start);
        request.addParameter("redirect", "/records/../invite-link");

        new AuthenticationReturnPathFilter().doFilter(request, new MockHttpServletResponse(), (incoming, response) -> { });
        AuthenticationReturnPathSession.capture(request);

        assertEquals("/invite-link", AuthenticationReturnPathSession.captured(request));
        AuthenticationReturnPathSession.capture(request);
        assertNull(AuthenticationReturnPathSession.captured(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example/invite", "//host", "/%2f%2fhost", "/%5chost", "/in\nvite", "/invite\u0000"})
    void unsafeStartDestinationsNeverBecomeAuthenticationReturnState(String destination) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/oauth2/authorization/google");
        request.addParameter("redirect", destination);

        new AuthenticationReturnPathFilter().doFilter(request, new MockHttpServletResponse(), (incoming, response) -> { });
        AuthenticationReturnPathSession.capture(request);

        assertNull(AuthenticationReturnPathSession.captured(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"org-7", "google"})
    void successfulEnterpriseOrSocialLoginReturnsToTheInvitationAfterSessionReplacement(String provider) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/oauth2/authorization/" + provider);
        request.addParameter("redirect", "/invite-link");
        AuthenticationReturnPathSession.remember(request);
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
        request.addParameter("redirect", "/invite");
        AuthenticationReturnPathSession.remember(request);
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
        request.addParameter("redirect", "/invite");
        AuthenticationReturnPathSession.remember(request);

        assertEquals("/auth/login?sso_error=1&redirect=%2Finvite", AuthenticationReturnPathSession.failurePath(request));
    }
}
