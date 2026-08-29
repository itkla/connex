package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.IssuedGrant;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.services.SocialLoginService;
import ooo.klae.connex.backend.services.SsoLinkService;
import ooo.klae.connex.backend.services.SsoLoginResult;
import ooo.klae.connex.backend.services.SsoLoginService;

/** Ensures a link-required IdP result cannot remain an authenticated application principal. */
@ExtendWith(MockitoExtension.class)
class SsoLinkAuthenticationSuccessHandlerTest {

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

    @Test
    void linkRequiredResultClearsAuthenticationBeforeIssuingFlow() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            oidcUser, List.of(), "org-7");
        SsoLoginResult.LinkRequired linkRequired = new SsoLoginResult.LinkRequired(
            19, "oidc", "https://issuer.example", "subject", 7);
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(oidcUser.getIssuer()).thenReturn(URI.create("https://issuer.example").toURL());
        when(oidcUser.getSubject()).thenReturn("subject");
        when(oidcUser.getEmail()).thenReturn("member@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getFullName()).thenReturn("Member");
        when(ssoLoginService.resolve(
            "oidc", "https://issuer.example", "subject", "member@example.com", true, 7, "Member"))
            .thenReturn(linkRequired);
        when(ssoLinkService.createChallenge(linkRequired)).thenReturn("raw-link-token");
        when(oneTimeLinkFlowCookie.ensureBrowserBinding(request, response))
            .thenReturn("browser-binding");
        when(oneTimeLinkFlowService.issue(
            request, "browser-binding", Purpose.SSO_LINK,
            ooo.klae.connex.backend.util.OneTimeTokenDigest.sha256("raw-link-token")))
            .thenReturn(new IssuedGrant("browser-grant", java.time.Duration.ofMinutes(10)));

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(authService).downgradeToUnauthenticatedSession(request, response);
        verify(oneTimeLinkFlowService).establishBrowserBinding(request, "browser-binding");
        verify(oneTimeLinkFlowCookie).set(
            response, Purpose.SSO_LINK, "browser-grant", java.time.Duration.ofMinutes(10));
        assertEquals("https://app.example/sso/link", response.getRedirectedUrl());
    }
}
