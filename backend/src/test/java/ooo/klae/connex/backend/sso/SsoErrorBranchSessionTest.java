package ooo.klae.connex.backend.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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

/**
 * Every SSO exit that is not a completed login must leave the session unauthenticated.
 *
 * <p>The upstream filter persists the identity provider's token before this handler runs. A branch
 * that only redirects leaves that session authenticated with a principal Connex did not issue: it
 * satisfies {@code authenticated()}, the session-epoch check skips it, and it is filed under no
 * revocation key, so nothing can enumerate or refuse it. It reaches the STOMP queues, which resolve
 * user destinations by principal name — a name the assertion supplied.
 */
@ExtendWith(MockitoExtension.class)
class SsoErrorBranchSessionTest {

    private static final String LOGIN_ERROR = "https://app.example/auth/login?sso_error=1";

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

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void anUnsupportedAuthenticationTypeLeavesNoAuthenticatedSession() throws Exception {
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");

        handler.onAuthenticationSuccess(request, response,
                new TestingAuthenticationToken("someone", "credentials"));

        assertRefusedBeforeResolving();
    }

    /**
     * A registration id that is not {@code org-<int>} must be refused outright.
     *
     * <p>Everything a successful login needs is stubbed leniently, so if the guard were removed the
     * ceremony would run to completion instead of throwing. That is what makes
     * {@code establishAuthenticatedSession} never being called a real assertion rather than an
     * accident of an unstubbed mock: a malformed registration must not be able to resolve to an
     * organization, which is a tenant boundary.
     */
    @Test
    void anUnknownOidcRegistrationCannotEstablishASession() throws Exception {
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        lenient().when(oidcUser.getIssuer()).thenReturn(URI.create("https://issuer.example").toURL());
        lenient().when(oidcUser.getSubject()).thenReturn("subject");
        lenient().when(oidcUser.getEmail()).thenReturn("member@example.com");
        lenient().when(oidcUser.getEmailVerified()).thenReturn(true);
        lenient().when(oidcUser.getFullName()).thenReturn("Member");
        lenient().when(ssoLoginService.resolve(
                any(), any(), any(), any(), anyBoolean(), anyInt(), any()))
                .thenReturn(new SsoLoginResult.Login(new User()));

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(oidcUser, List.of(), "not-an-org"));

        assertRefusedBeforeResolving();
        verify(authService, never()).establishAuthenticatedSession(any(), any(), any());
    }

    @Test
    void socialLoginWithoutAUsableEmailLeavesNoAuthenticatedSession() throws Exception {
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(oidcUser.getEmail()).thenReturn(null);

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(oidcUser, List.of(), "google"));

        assertRefusedBeforeResolving();
    }

    /**
     * A resolution that throws must not leave the session authenticated either. Before this, a
     * {@code ForbiddenException} escaped the handler as a 500 with the identity provider's principal
     * still signed in.
     */
    @Test
    void aFailedResolutionLeavesNoAuthenticatedSession() throws Exception {
        when(mailProperties.getAppBaseUrl()).thenReturn("https://app.example");
        when(oidcUser.getIssuer()).thenReturn(URI.create("https://issuer.example").toURL());
        when(oidcUser.getSubject()).thenReturn("subject");
        when(oidcUser.getEmail()).thenReturn("member@example.com");
        when(oidcUser.getEmailVerified()).thenReturn(true);
        when(oidcUser.getFullName()).thenReturn("Member");
        when(ssoLoginService.resolve(
                "oidc", "https://issuer.example", "subject", "member@example.com", true, 7, "Member"))
                .thenThrow(new ForbiddenException("connection vanished"));

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(oidcUser, List.of(), "org-7"));

        assertDowngraded();
    }

    private void assertDowngraded() {
        verify(authService).downgradeToUnauthenticatedSession(request, response);
        assertEquals(LOGIN_ERROR, response.getRedirectedUrl());
    }

    /**
     * The branch must refuse before it resolves anything.
     *
     * <p>Without this, a mutation that let a malformed registration reach resolution would still
     * satisfy the assertions above: the unstubbed resolver returns null, {@code completeResolution}
     * throws, and the catch performs the same downgrade and redirect. The tests would pass while
     * the direct guard they exist for had been removed.
     */
    private void assertRefusedBeforeResolving() {
        assertDowngraded();
        verifyNoInteractions(ssoLoginService, socialLoginService, ssoLinkService, auditService);
    }
}
