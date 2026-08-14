package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

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

import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.util.ClientIpResolver;

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
            new OneTimeLinkFlowService().replaceSessionPreservingFlows(request);
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
    void accountSessionReplacementPreservesOnlyThePurposeBoundFlow() {
        OneTimeLinkFlowService service = new OneTimeLinkFlowService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        OneTimeLinkFlowService.IssuedGrant grant = service.issue(
            request, OneTimeLinkFlowService.Purpose.WORKSPACE_INVITE, "source-hash");
        String originalSessionId = request.getSession(false).getId();
        request.getSession(false).setAttribute("unrelated", "must-not-transfer");

        service.replaceSessionPreservingFlows(request);

        assertNotEquals(originalSessionId, request.getSession(false).getId());
        assertNull(request.getSession(false).getAttribute("unrelated"));
        assertEquals(
            "source-hash",
            service.require(
                request,
                OneTimeLinkFlowService.Purpose.WORKSPACE_INVITE,
                grant.value()));
    }
}
