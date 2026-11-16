package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.integration.SoftwarePasskeys.SoftwarePasskey;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.webauthn.EnrollmentEvidence;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * The operator break-glass ceremony is the documented route back for a privileged account that
 * cannot satisfy the emailed first-enrollment confirmation — the instance cannot send mail, or the
 * mailbox is unreachable. Such an account has never enrolled, so recovery has no credential to
 * remove, and refusing it there left the route unexecutable.
 */
class NeverEnrolledBreakGlassIntegrationTest extends AbstractPrivilegedMfaRecoveryIntegrationTest {
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private PrivilegedAccountService privilegedAccountService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SessionRepository<? extends Session> sessionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    /**
     * The whole point: the ceremony must complete for an account with nothing to remove, and must
     * leave that session able to enroll the replacement.
     */
    @Test
    void breakGlassCompletesForAnAccountThatHasNeverEnrolled() throws Exception {
        User admin = privilegedPasswordAccount();
        assertFalse(webAuthnService.hasPasskey(admin.getId()));
        MockHttpSession session = authenticatedSession(admin);
        String recoveryToken = issueRecoveryToken(admin);

        mockMvc.perform(post("/api/auth/webauthn/recover")
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\""
                                + recoveryToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The operator proof is still load-bearing: the password alone must not complete the ceremony.
     */
    @Test
    void breakGlassStillRequiresTheOperatorToken() throws Exception {
        User admin = privilegedPasswordAccount();
        issueRecoveryToken(admin);

        mockMvc.perform(post("/api/auth/webauthn/recover")
                        .session(authenticatedSession(admin))
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\"wrong\"}"))
                .andExpect(status().isForbidden());
    }

    /** A workspace-only owner with no cutover passkey has operator recovery as its approval path. */
    @Test
    void aWorkspaceOnlyOwnerWithoutCutoverCoverageIsSatisfiedByBreakGlassReplacement() throws Exception {
        String configured = privilegedMfaProperties.getAttestationEnforced();
        try {
            privilegedMfaProperties.setAttestationEnforced("true");
            User owner = privilegedPasswordAccount();
            assertFalse(webAuthnService.hasPasskey(owner.getId()));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM org_member WHERE user_id = ?", Integer.class, owner.getId()));
            SoftwarePasskey uncovered = SoftwarePasskeys.register(
                    webAuthnService, userMapper, owner, new EnrollmentEvidence(null, null), "Uncovered");
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM privileged_credential_attestation WHERE credential_row_id = ?",
                    Integer.class, uncovered.rowId()));
            assertEquals(PrivilegedMfaStanding.ATTESTATION_REQUIRED,
                    privilegedAccountService.enforcedStanding(owner.getId()));
            MockHttpSession session = authenticatedSession(owner);
            String recoveryToken = issueRecoveryToken(owner);

            mockMvc.perform(post("/api/auth/webauthn/recover")
                            .session(session)
                            .with(csrf().asHeader())
                            .contentType("application/json")
                            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\""
                                    + recoveryToken + "\"}"))
                    .andExpect(status().isOk());

            assertFalse(webAuthnService.hasPasskey(owner.getId()));
            assertEquals(PrivilegedMfaStanding.ENROLLMENT_REQUIRED,
                    privilegedAccountService.enforcedStanding(owner.getId()));
            MockHttpServletRequest enrollment = new MockHttpServletRequest(context.getServletContext());
            enrollment.setSession(session);
            String sessionPrimaryId = sessionSecurityService.sessionPrimaryId(enrollment);
            assertNotNull(sessionPrimaryId);
            SoftwarePasskey replacement = SoftwarePasskeys.register(webAuthnService, userMapper, owner,
                    new EnrollmentEvidence(sessionPrimaryId, null), "Replacement");

            assertEquals("BREAK_GLASS", jdbcTemplate.queryForObject(
                    "SELECT privileged_assurance FROM webauthn_credential WHERE id = ?",
                    String.class, replacement.rowId()));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM privileged_credential_attestation WHERE credential_row_id = ?",
                    Integer.class, replacement.rowId()));
            assertEquals(PrivilegedMfaStanding.SATISFIED,
                    privilegedAccountService.enforcedStanding(owner.getId()));
        } finally {
            privilegedMfaProperties.setAttestationEnforced(configured);
        }
    }

    /**
     * Issues a fresh token bound to one account, as the operator runbook does.
     *
     * @param account the account the token may recover
     * @return the raw token handed to the account holder
     */
    private String issueRecoveryToken(User account) {
        String token = UUID.randomUUID().toString();
        privilegedMfaProperties.setRecoveryTokenSha256(
                sha256Hex("connex-privileged-mfa-recovery:v1:" + account.getId() + ":" + token));
        return token;
    }

    private MockHttpSession authenticatedSession(User account) {
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        request.setSession(new MockHttpSession(context.getServletContext()));
        authService.establishAuthenticatedSession(account, request, new MockHttpServletResponse());
        MockHttpSession established = (MockHttpSession) request.getSession(false);
        MockHttpSession stored = new MockHttpSession(
                context.getServletContext(), createStored(sessionRepository, account));
        Collections.list(established.getAttributeNames())
                .forEach(name -> stored.setAttribute(name, established.getAttribute(name)));
        return stored;
    }

    private static <S extends Session> String createStored(
            SessionRepository<S> repository, User principal) {
        S created = repository.createSession();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        created.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        repository.save(created);
        return created.getId();
    }

    private User privilegedPasswordAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("bg_" + suffix);
        user.setDisplayName("BreakGlass " + suffix);
        user.setEmail("bg_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        jdbcTemplate.update(
                "INSERT INTO organization (name, slug) VALUES (?, ?)", "Org " + suffix, "org-" + suffix);
        Integer orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug = ?", Integer.class, "org-" + suffix);
        assertNotNull(orgId);
        jdbcTemplate.update(
                "INSERT INTO workspace (name, slug, org_id) VALUES (?, ?, ?)",
                "Workspace " + suffix, "ws-" + suffix, orgId);
        Integer workspaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE slug = ?", Integer.class, "ws-" + suffix);
        assertNotNull(workspaceId);
        workspaceMapper.addMember(workspaceId, user.getId(), "owner");
        User persisted = userMapper.getUserById(user.getId());
        assertNotNull(persisted);
        assertEquals(0, persisted.getSessionEpoch());
        return persisted;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
