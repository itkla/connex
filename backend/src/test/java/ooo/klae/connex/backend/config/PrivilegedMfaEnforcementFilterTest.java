package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

class PrivilegedMfaEnforcementFilterTest {
    private final PrivilegedMfaProperties properties = mock(PrivilegedMfaProperties.class);
    private final PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final FilterChain filterChain = mock(FilterChain.class);
    private PrivilegedMfaEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        when(properties.isEnforced()).thenReturn(true);
        filter = new PrivilegedMfaEnforcementFilter(
                properties,
                privilegedAccountService,
                webAuthnService,
                sessionSecurityService,
                auditService);
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unenrolledPrivilegedAccountIsConfinedToEnrollment() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);
        MockHttpServletResponse response = execute("GET", "/api/companies");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(
                PrivilegedMfaEnforcementFilter.ENROLLMENT_REQUIRED_CODE));
        verify(filterChain, never()).doFilter(any(), any());
        verify(auditService).recordFailureScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    @Test
    void unenrolledPrivilegedAccountMayUseEnrollmentAndAccountPaths() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        execute("POST", "/api/auth/webauthn/register/options");
        execute("GET", "/api/auth/me");
        execute("GET", "/api/workspaces");
        execute("POST", "/api/auth/logout");

        verify(filterChain, org.mockito.Mockito.times(4)).doFilter(any(), any());
    }

    @Test
    void nonPrivilegedAccountIsNotConfined() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(false);

        execute("GET", "/api/companies");

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void promotionAndDemotionApplyOnTheNextRequest() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(false, true, false);

        execute("GET", "/api/companies");
        MockHttpServletResponse promoted = execute("GET", "/api/companies");
        execute("GET", "/api/companies");

        assertEquals(403, promoted.getStatus());
        verify(privilegedAccountService, org.mockito.Mockito.times(3)).isPrivileged(7);
        verify(filterChain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    }

    @Test
    void federatedSessionDoesNotSubstituteForWebauthnStepUp() throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(sessionSecurityService.hasFreshRecentAuthentication(isNull(), eq(7))).thenReturn(false);

        MockHttpServletResponse response = execute("GET", "/api/exports/persons");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void freshPasskeyStepUpAllowsHighRiskExport() throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(sessionSecurityService.hasFreshRecentAuthentication(isNull(), eq(7))).thenReturn(true);

        execute("GET", "/api/reports/4/snapshots/8/export.csv");

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void tenantSelectionContinuesToTheExistingIsolationLayer() throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        MockHttpServletRequest request = request("GET", "/api/companies");
        request.addHeader("X-Workspace-Id", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void exportMatcherCoversEveryExportSurface() {
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/exports/deals"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/orgs/2/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/campaigns/3/exports"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/campaigns/3/exports/9"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("/api/reports/4/export.csv"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp(
                "/api/reports/4/snapshots/8/export.csv"));
    }

    private MockHttpServletResponse execute(String method, String path) throws ServletException, IOException {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
