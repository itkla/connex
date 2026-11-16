package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@SpringBootTest
class PrivilegedMfaHttpEnforcementIntegrationTest {
    private static final int USER_ID = 9137;
    private static final int UNAPPROVED_USER_ID = 9138;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;

    @MockitoBean private PrivilegedAccountService privilegedAccountService;
    @MockitoBean private WebAuthnService webAuthnService;
    @MockitoBean private AuthService authService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private UserMapper userMapper;

    private MockMvc mockMvc;
    private User privilegedUser;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        privilegedUser = new User();
        privilegedUser.setId(USER_ID);
        privilegedUser.setUsername("unenrolled-privileged-integration");
        privilegedUser.setDisplayName("Unenrolled Privileged Integration");
        authentication = new UsernamePasswordAuthenticationToken(privilegedUser, null, List.of());
        when(privilegedAccountService.enforcedStanding(USER_ID))
                .thenReturn(PrivilegedMfaStanding.ENROLLMENT_REQUIRED);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(false);
        when(authService.getCurrentUser()).thenReturn(privilegedUser);
        when(authService.hasPasswordCredential(USER_ID)).thenReturn(true);
        when(userMapper.currentSessionEpoch(USER_ID)).thenReturn(0);
    }

    /**
     * A session as a real authenticated request carries it: stamped with the epoch the credential
     * was verified against. Without the stamp {@code SessionEpochFilter} de-authenticates first and
     * every assertion here would see 401 instead of the confinement it is testing.
     */
    private MockHttpSession stampedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, 0);
        return session;
    }

    /**
     * Runs the real security filter chain. Both confined requests are refused, but the second comes
     * from the same address inside the denial-audit window, so only the first is audited (#1850). The
     * limiter is a singleton in this cached context: another test confining the same user from the same
     * address would see no audit row, so it must use a different user or address.
     */
    @Test
    void testProfileDefaultsToEnforcedAndConfinesUnenrolledPrivilegedAccount() throws Exception {
        assertTrue(privilegedMfaProperties.isEnforced());

        mockMvc.perform(get("/api/audit/export").session(stampedSession()).with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));

        mockMvc.perform(get("/api/companies").session(stampedSession()).with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));

        mockMvc.perform(get("/api/auth/webauthn/register/requirements")
                        .session(stampedSession()).with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPasswordRequired").value(true))
                .andExpect(jsonPath("$.standing").value("ENROLLMENT_REQUIRED"))
                .andExpect(jsonPath("$.approvalGaps").isArray());

        verify(auditService, times(1)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(USER_ID), isNull(), isNull(),
                eq("Unenrolled Privileged Integration"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"), any(HttpServletRequest.class), any());
    }

    @Test
    void differentBrowsersAtOneAddressKeepSeparateDenialEvidenceThroughTheSecurityChain() throws Exception {
        for (String agent : List.of("Browser A", "Browser B", "Browser A", "Browser B")) {
            mockMvc.perform(get("/api/companies").session(stampedSession()).with(authentication(authentication))
                            .header("User-Agent", agent).with(request -> {
                                request.setRemoteAddr("192.0.2.64");
                                return request;
                            }))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));
        }

        verify(auditService, times(2)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(USER_ID), isNull(), isNull(),
                eq("Unenrolled Privileged Integration"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"), any(HttpServletRequest.class), eq("192.0.2.64"));
    }

    /**
     * Through the real filter chain, a privileged account whose passkeys are not yet approved in
     * every organization where it is privileged is confined with the attestation code, and the
     * refusal is audited with its own reason (#1534). It uses its own account, so the shared
     * denial-audit limiter cannot suppress the row.
     */
    @Test
    void anUnapprovedPrivilegedAccountIsConfinedWithTheAttestationCode() throws Exception {
        User unapproved = new User();
        unapproved.setId(UNAPPROVED_USER_ID);
        unapproved.setUsername("unapproved-privileged-integration");
        unapproved.setDisplayName("Unapproved Privileged Integration");
        when(privilegedAccountService.enforcedStanding(UNAPPROVED_USER_ID))
                .thenReturn(PrivilegedMfaStanding.ATTESTATION_REQUIRED);
        when(authService.getCurrentUser()).thenReturn(unapproved);
        when(userMapper.currentSessionEpoch(UNAPPROVED_USER_ID)).thenReturn(0);
        UsernamePasswordAuthenticationToken unapprovedAuthentication =
                new UsernamePasswordAuthenticationToken(unapproved, null, List.of());

        mockMvc.perform(get("/api/companies").session(stampedSession()).with(authentication(unapprovedAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ATTESTATION_REQUIRED"));

        verify(auditService, times(1)).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(UNAPPROVED_USER_ID), isNull(), isNull(),
                eq("Unapproved Privileged Integration"), eq("Privileged account confined pending passkey approval"),
                eq("attestation_required"), any(HttpServletRequest.class), any());
    }

    @Test
    void crossSiteFetchMetadataReachesDenialAuditingThroughTheSecurityChain() throws Exception {
        mockMvc.perform(get("/api/companies").session(stampedSession()).with(authentication(authentication))
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.36");
                            return request;
                        })
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Dest", "image"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));

        ArgumentCaptor<HttpServletRequest> deniedRequest = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(auditService).recordStrictFailureIndependentScoped(
                eq("auth.mfa.policy.denied"), eq("user"), eq(USER_ID), isNull(), isNull(),
                eq("Unenrolled Privileged Integration"), eq("Privileged account confined pending MFA enrollment"),
                eq("enrollment_required"), deniedRequest.capture(), any());
        assertEquals("cross-site", deniedRequest.getValue().getHeader("Sec-Fetch-Site"));
        assertEquals("no-cors", deniedRequest.getValue().getHeader("Sec-Fetch-Mode"));
        assertEquals("image", deniedRequest.getValue().getHeader("Sec-Fetch-Dest"));
    }

    /**
     * Through the real filter chain, a confined account still reaches the two attestation
     * redemption routes (#1534), and CSRF still guards them. Their neighbours stay confined. This
     * proves only that the routes are reachable; confining an enrolled account whose passkey lacks
     * coverage belongs to enforcement.
     */
    @Test
    void aConfinedAccountReachesOnlyTheAttestationRedemptionRoutesAndOnlyWithCsrf() throws Exception {
        mockMvc.perform(post("/api/auth/webauthn/attestation/options")
                        .session(stampedSession()).with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("PRIVILEGED_MFA"))));
        mockMvc.perform(post("/api/auth/webauthn/attestation/options")
                        .session(stampedSession()).with(authentication(authentication)).with(csrf().asHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/webauthn/attestation")
                        .session(stampedSession()).with(authentication(authentication)).with(csrf().asHeader())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/webauthn/attestation")
                        .session(stampedSession()).with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));
        mockMvc.perform(post("/api/workspaces/3/members/9/mfa-attestations")
                        .session(stampedSession()).with(authentication(authentication)).with(csrf().asHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));
    }
}
