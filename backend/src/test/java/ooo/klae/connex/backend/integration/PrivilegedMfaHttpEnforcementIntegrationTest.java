package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@SpringBootTest
class PrivilegedMfaHttpEnforcementIntegrationTest {
    private static final int USER_ID = 9137;

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;

    @MockitoBean private PrivilegedAccountService privilegedAccountService;
    @MockitoBean private WebAuthnService webAuthnService;
    @MockitoBean private AuthService authService;
    @MockitoBean private AuditService auditService;

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
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(false);
        when(authService.getCurrentUser()).thenReturn(privilegedUser);
        when(authService.hasPasswordCredential(USER_ID)).thenReturn(true);
    }

    @Test
    void testProfileDefaultsToEnforcedAndConfinesUnenrolledPrivilegedAccount() throws Exception {
        assertTrue(privilegedMfaProperties.isEnforced());

        mockMvc.perform(get("/api/audit/export").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));

        mockMvc.perform(get("/api/companies").with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGED_MFA_ENROLLMENT_REQUIRED"));

        mockMvc.perform(get("/api/auth/webauthn/register/requirements")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPasswordRequired").value(true));

        verify(auditService, times(2)).recordFailureScoped(
                "auth.mfa.policy.denied",
                "user",
                USER_ID,
                null,
                null,
                "Unenrolled Privileged Integration",
                "Privileged account confined pending MFA enrollment",
                "enrollment_required");
    }
}
