package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;

import ooo.klae.connex.backend.mappers.OneTimeLinkFlowMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Verifies that SSO linking is anonymous and survives only deliberate account-session rotation. */
@ExtendWith(MockitoExtension.class)
class SsoLinkFlowSecurityTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private AuditService auditService;
    @Mock private WorkspaceService workspaceService;
    @Mock private LoginRateLimiter loginRateLimiter;
    @Mock private ClientIpResolver clientIpResolver;
    @Mock private RegistrationVerificationService registrationVerificationService;
    @Mock private SsoConnectionService ssoConnectionService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private OneTimeLinkFlowService oneTimeLinkFlowService;
    @Mock private WorkspaceCookie workspaceCookie;
    @InjectMocks private AuthService authService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void linkingPreparationClearsUpstreamAuthenticationAndUnrelatedSessionState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = request.getSession(true);
        session.setAttribute("unrelated", "must-not-transfer");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("upstream", null, java.util.List.of()));
        SecurityContextHolder.setContext(context);
        doAnswer(invocation -> {
            new OneTimeLinkFlowService(
                mock(OneTimeLinkFlowMapper.class),
                mock(OneTimeLinkFlowClaimService.class))
                .replaceSessionPreservingFlows(request);
            return null;
        }).when(oneTimeLinkFlowService).replaceSessionPreservingFlows(request);

        authService.prepareUnauthenticatedLinkFlow(request, response);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotSame(session, request.getSession(false));
        assertNull(request.getSession(false).getAttribute("unrelated"));
        verify(oneTimeLinkFlowService).replaceSessionPreservingFlows(request);
        verify(sessionSecurityService).clearAuthenticationState(request);
        verify(workspaceCookie).clear(response);
    }

    @Test
    void accountSessionReplacementLeavesTheDurableBrowserFlowAvailable() {
        OneTimeLinkFlowMapper flowMapper = mock(OneTimeLinkFlowMapper.class);
        OneTimeLinkFlowService service = new OneTimeLinkFlowService(
            flowMapper, mock(OneTimeLinkFlowClaimService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        String browserBinding = OneTimeTokenDigest.generate();
        String sourceHash = OneTimeTokenDigest.sha256("source-token");
        request.setCookies(new Cookie(
            OneTimeLinkFlowService.BROWSER_BINDING_COOKIE, browserBinding));
        service.establishBrowserBinding(request, browserBinding);
        OneTimeLinkFlowService.IssuedGrant grant = service.issue(
            request, OneTimeLinkFlowService.Purpose.WORKSPACE_INVITE, sourceHash);
        String grantHash = OneTimeTokenDigest.sha256(grant.value());
        String exchangeOwnerHash = service.exchangeOwnerHash(request);
        when(flowMapper.findValidSourceTokenHash(
            grantHash,
            exchangeOwnerHash,
            OneTimeLinkFlowService.Purpose.WORKSPACE_INVITE.name()))
            .thenReturn(sourceHash);
        String originalSessionId = request.getSession(false).getId();
        request.getSession(false).setAttribute("unrelated", "must-not-transfer");

        service.replaceSessionPreservingFlows(request);

        assertNotEquals(originalSessionId, request.getSession(false).getId());
        assertNull(request.getSession(false).getAttribute("unrelated"));
        assertEquals(
            sourceHash,
            service.require(
                request,
                OneTimeLinkFlowService.Purpose.WORKSPACE_INVITE,
                grant.value()));
    }

    @Test
    void responseLocalSsoBindingCanIssueAFlowBeforeTheCookieReturns() {
        OneTimeLinkFlowMapper flowMapper = mock(OneTimeLinkFlowMapper.class);
        OneTimeLinkFlowService service = new OneTimeLinkFlowService(
            flowMapper, mock(OneTimeLinkFlowClaimService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        String browserBinding = OneTimeTokenDigest.generate();
        String sourceHash = OneTimeTokenDigest.sha256("sso-source-token");

        service.establishBrowserBinding(request, browserBinding);
        OneTimeLinkFlowService.IssuedGrant grant = service.issue(
            request,
            browserBinding,
            OneTimeLinkFlowService.Purpose.SSO_LINK,
            sourceHash);

        verify(flowMapper).upsert(
            eq(OneTimeTokenDigest.sha256(grant.value())),
            matches("[0-9a-f]{64}"),
            eq(OneTimeLinkFlowService.Purpose.SSO_LINK.name()),
            eq(sourceHash),
            eq(600L));
    }
}
