package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DenialAuditRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

class PrivilegedMfaEnforcementFilterTest {
    private final PrivilegedMfaProperties properties = mock(PrivilegedMfaProperties.class);
    private final PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SessionSecurityService sessionSecurityService = spy(new SessionSecurityService(
            new SessionSecurityProperties(), properties, Clock.systemUTC(),
            mock(UserMapper.class), mock(SpringSessionMapper.class)));
    private final AuditService auditService = mock(AuditService.class);
    private final DenialAuditRateLimiter denialAuditRateLimiter =
            new DenialAuditRateLimiter(3_600, Clock.systemUTC(), new SimpleMeterRegistry());
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
                auditService,
                denialAuditRateLimiter,
                new ClientIpResolver(""));
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
        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    /**
     * A cross-site navigation carries the {@code SameSite=Lax} session cookie, so another site can
     * replay this denial at will. Every request is still refused, but the audit trail records the
     * confinement once per window rather than once per forged visit (#1850).
     */
    @Test
    void repeatedConfinementDenialsFromOneAddressAreAuditedOnce() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());
        }

        verify(filterChain, never()).doFilter(any(), any());
        verify(auditService, times(1)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    @Test
    void aConfinementDenialFromAnotherAddressIsAuditedAgain() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());
        assertEquals(403, execute("GET", "/api/companies", "198.51.100.9").getStatus());

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    @Test
    void repeatedStepUpDenialsFromOneAddressAreAuditedOnce() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(403, execute("GET", "/api/audit/export", "203.0.113.5").getStatus());
        }
        assertEquals(403, execute("GET", "/api/audit/export", "198.51.100.9").getStatus());

        verify(filterChain, never()).doFilter(any(), any());
        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
                eq(AuditService.EXPORT_STEP_UP_ACTION), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq(AuditService.EXPORT_STEP_UP_SUMMARY), eq("step_up_required"));
    }

    /**
     * The key is the address {@link ClientIpResolver} resolves, which is the one the audit row records,
     * not the proxy's: behind a trusted proxy, two clients are two addresses.
     */
    @Test
    void denialsAreKeyedOnTheResolvedClientAddressBehindATrustedProxy() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);
        PrivilegedMfaEnforcementFilter proxied = new PrivilegedMfaEnforcementFilter(
                properties,
                privilegedAccountService,
                webAuthnService,
                sessionSecurityService,
                auditService,
                new DenialAuditRateLimiter(3_600, Clock.systemUTC(), new SimpleMeterRegistry()),
                new ClientIpResolver("10.0.0.0/8"));

        for (String client : List.of("203.0.113.5", "203.0.113.5", "198.51.100.9")) {
            MockHttpServletRequest request = request("GET", "/api/companies");
            request.setRemoteAddr("10.0.0.2");
            request.addHeader("X-Forwarded-For", client);
            MockHttpServletResponse response = new MockHttpServletResponse();
            proxied.doFilter(request, response, filterChain);
            assertEquals(403, response.getStatus());
        }

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    /**
     * A denial whose audit row could not be written must not silence the trail for the rest of the
     * window, and the failure must never turn the refusal into anything but a 403.
     */
    @Test
    void aDenialWhoseAuditFailedIsAuditedByTheNextOne() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);
        doThrow(new IllegalStateException("audit unavailable"))
                .doNothing()
                .when(auditService).recordStrictFailureIndependentScoped(
                        any(), any(), any(), any(), any(), any(), any(), any());

        assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());
        assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());
        assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
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
    void unenrolledPrivilegedAccountMayReachTheEnrollmentConfirmationEndpoints() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        execute("POST", "/api/auth/webauthn/register/confirmation");
        execute("POST", "/api/auth/webauthn/register/confirmation/exchange");

        verify(filterChain, org.mockito.Mockito.times(2)).doFilter(any(), any());
    }

    @ParameterizedTest
    @MethodSource("linkFlowPaths")
    void unenrolledPrivilegedAccountMayOpenEmailedLinkFlows(String method, String path) throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        MockHttpServletResponse response = execute(method, path);

        assertEquals(200, response.getStatus());
        verify(filterChain).doFilter(any(), any());
        verifyNoInteractions(auditService);
    }

    private static Stream<Arguments> linkFlowPaths() {
        return Stream.of(
                Arguments.of("POST", "/api/document-acceptance/exchange"),
                Arguments.of("GET", "/api/document-acceptance"),
                Arguments.of("POST", "/api/document-acceptance/viewed"),
                Arguments.of("POST", "/api/document-acceptance/accept"),
                Arguments.of("POST", "/api/document-acceptance/decline;x"),
                Arguments.of("POST", "/api/delivery/unsubscribe/exchange"),
                Arguments.of("GET", "/api/delivery/unsubscribe"),
                Arguments.of("POST", "/api/delivery/unsubscribe"));
    }

    @Test
    void linkFlowExemptionDoesNotCoverPrefixLookalikes() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);

        MockHttpServletResponse acceptance = execute("GET", "/api/document-acceptances");
        MockHttpServletResponse delivery = execute("GET", "/api/delivery/unsubscribed");

        assertEquals(403, acceptance.getStatus());
        assertEquals(403, delivery.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
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
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/exports/deals"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/orgs/2/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("POST", "/api/campaigns/3/exports"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("POST", "/api/reports/4/export.csv"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp(
                "GET", "/api/reports/4/snapshots/8/export.csv"));
    }

    @Test
    void nonDecimalIdentifiersSpringAcceptsAreStillGated() {
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/orgs/0x1/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/orgs/+1/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/orgs/%231/audit/export"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("POST", "/api/campaigns/0x3/exports"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp("POST", "/api/reports/0x4/export.csv"));
        assertTrue(PrivilegedMfaEnforcementFilter.requiresExportStepUp(
                "GET", "/api/reports/+4/snapshots/0x8/export.csv"));
    }

    @Test
    void nonDecimalIdentifierExportIsRefusedOverTheFilterChain() throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(sessionSecurityService.hasFreshRecentAuthentication(isNull(), eq(7))).thenReturn(false);

        MockHttpServletResponse response = execute("GET", "/api/orgs/0x1/audit/export");

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void campaignExportMetadataReadsAreNotGated() {
        assertFalse(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/campaigns/3/exports"));
        assertFalse(PrivilegedMfaEnforcementFilter.requiresExportStepUp("GET", "/api/campaigns/3/exports/9"));
    }

    @Test
    void campaignExportMetadataReadReachesTheChainWithoutStepUp() throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);

        MockHttpServletResponse response = execute("GET", "/api/campaigns/3/exports");

        assertEquals(200, response.getStatus());
        verify(filterChain).doFilter(any(), any());
    }

    @ParameterizedTest
    @MethodSource("matrixSuffixedExportPaths")
    void matrixSuffixedExportPathsStillRequirePasskeyStepUp(String method, String path) throws Exception {
        when(webAuthnService.hasPasskey(7)).thenReturn(true);
        when(sessionSecurityService.hasFreshRecentAuthentication(isNull(), eq(7))).thenReturn(false);

        MockHttpServletResponse response = execute(method, path);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    private static Stream<Arguments> matrixSuffixedExportPaths() {
        List<Arguments> surfaces = List.of(
                Arguments.of("GET", "/api/exports/deals"),
                Arguments.of("GET", "/api/audit/export"),
                Arguments.of("GET", "/api/orgs/2/audit/export"),
                Arguments.of("POST", "/api/campaigns/3/exports"),
                Arguments.of("POST", "/api/reports/4/export.csv"),
                Arguments.of("GET", "/api/reports/4/snapshots/8/export.csv"));
        return surfaces.stream().flatMap(surface -> Stream.of(";x", "%3Bx")
                .map(suffix -> Arguments.of(surface.get()[0], surface.get()[1] + suffix)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/%65xports/persons", "/%61pi/exports/persons", "/api//exports/persons",
            "/api/%61udit/export", "/api/orgs/2/%61udit/export",
            "/api/reports/4/%65xport.csv", "/api/reports/4/snapshots/8/%65xport.csv"})
    void encodedExportsRequireTheCanonicalStepUp(String path) throws Exception {
        MockHttpServletResponse response = execute("GET", path);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void contextPathExportStillRequiresStepUp() throws Exception {
        MockHttpServletRequest request = request("GET", "/connex/api/exports/persons");
        request.setContextPath("/connex");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void encodedCampaignExportRequiresStepUp() throws Exception {
        MockHttpServletResponse response = execute("POST", "/api/campaigns/3/%65xports");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("RECENT_AUTHENTICATION_REQUIRED"));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exportPolicyRespectsRolloutFlagForCanonicalAndEncodedPaths(boolean enforced) throws Exception {
        when(properties.isEnforced()).thenReturn(enforced);
        for (String path : List.of("/api/exports/persons", "/api/%65xports/persons")) {
            MockHttpServletResponse response = execute("GET", path);
            assertEquals(enforced ? 403 : 200, response.getStatus(), path);
        }
        verify(filterChain, org.mockito.Mockito.times(enforced ? 0 : 2)).doFilter(any(), any());
        verify(sessionSecurityService, org.mockito.Mockito.times(2)).isExportStepUpSatisfied(null, 7);
    }

    /**
     * An {@link Error} escaping the audit write must release the window as well; otherwise one dead
     * write would silence the trail for the rest of the hour.
     */
    @Test
    void aDenialWhoseAuditWriteDiedWithAnErrorIsAuditedByTheNextOne() throws Exception {
        when(privilegedAccountService.isPrivileged(7)).thenReturn(true);
        doThrow(new LinkageError("audit write died"))
                .doNothing()
                .when(auditService).recordStrictFailureIndependentScoped(
                        any(), any(), any(), any(), any(), any(), any(), any());

        assertThrows(LinkageError.class, () -> execute("GET", "/api/companies", "203.0.113.5"));
        assertEquals(403, execute("GET", "/api/companies", "203.0.113.5").getStatus());

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(7), isNull(), isNull(),
                eq("Admin"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"));
    }

    private MockHttpServletResponse execute(String method, String path) throws ServletException, IOException {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private MockHttpServletResponse execute(String method, String path, String remoteAddress)
            throws ServletException, IOException {
        MockHttpServletRequest request = request(method, path);
        request.setRemoteAddr(remoteAddress);
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
