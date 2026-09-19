package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * With confinement switched off, a password-only session still cannot move a privileged,
 * never-enrolled account's email address (#1506 Part A).
 *
 * <p>Confinement is disabled here on purpose: under the default posture the enforcement filter
 * refuses this endpoint first, and its refusal would satisfy these assertions whatever the service
 * does. With it off, the {@code PASSKEY_ENROLLMENT_REQUIRED} code can only come from the
 * email-change gate itself.
 */
@SpringBootTest(properties = {
    "connex.security.privileged-mfa.enforced=false",
    "connex.security.privileged-mfa.change-actor=privileged-email-change-integration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrivilegedEmailChangeStepUpIntegrationTest {
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final Duration NO_SELF_LOCK = Duration.ofSeconds(15);

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;
    @Autowired private UserMapper userMapper;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The refusal and its audit complete promptly: the audit is appended before the request takes
     * its own account lock, so it cannot wait on that lock until the InnoDB timeout. Once the
     * account no longer holds privilege, the same password-only request is accepted.
     */
    @Test
    void aPasswordOnlySessionCannotMoveAnUnenrolledPrivilegedAccountsAddress() throws Exception {
        assertFalse(privilegedMfaProperties.isEnforced());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        int workspaceId = freshWorkspace(suffix);
        User admin = passwordAccount(suffix);
        workspaceMapper.addMember(workspaceId, admin.getId(), "admin");
        assertFalse(webAuthnService.hasPasskey(admin.getId()));
        MockHttpSession session = authenticatedSession(admin);

        assertTimeoutPreemptively(NO_SELF_LOCK, () -> requestEmailChange(session, workspaceId, suffix)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSKEY_ENROLLMENT_REQUIRED")));

        assertEquals(0, count("SELECT COUNT(*) FROM email_change_token WHERE user_id = ?", admin.getId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'auth.email_change.refused'"
                + " AND entity_id = ?", admin.getId()));

        jdbcTemplate.update("UPDATE workspace_member SET role = 'member' WHERE workspace_id = ? AND user_id = ?",
                workspaceId, admin.getId());

        requestEmailChange(session, workspaceId, suffix).andExpect(status().isOk());
        assertEquals(1, count("SELECT COUNT(*) FROM email_change_token WHERE user_id = ?", admin.getId()));
    }

    private ResultActions requestEmailChange(MockHttpSession session, int workspaceId, String suffix)
            throws Exception {
        return mockMvc.perform(post("/api/users/me/email-change")
                .session(session)
                .with(csrf().asHeader())
                .header("X-Workspace-Id", String.valueOf(workspaceId))
                .contentType("application/json")
                .content("{\"newEmail\":\"moved_" + suffix + "@example.com\",\"currentPassword\":\""
                        + PASSWORD + "\"}"));
    }

    private int count(String sql, int userId) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, userId);
        assertNotNull(count);
        return count;
    }

    private int freshWorkspace(String suffix) {
        jdbcTemplate.update(
                "INSERT INTO organization (name, slug) VALUES (?, ?)", "Org " + suffix, "pec-org-" + suffix);
        Integer orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug = ?", Integer.class, "pec-org-" + suffix);
        assertNotNull(orgId);
        jdbcTemplate.update(
                "INSERT INTO workspace (name, slug, org_id) VALUES (?, ?, ?)",
                "Workspace " + suffix, "pec-ws-" + suffix, orgId);
        Integer workspaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE slug = ?", Integer.class, "pec-ws-" + suffix);
        assertNotNull(workspaceId);
        return workspaceId;
    }

    private User passwordAccount(String suffix) {
        User user = new User();
        user.setUsername("pec_" + suffix);
        user.setDisplayName("Email Change " + suffix);
        user.setEmail("pec_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        User persisted = userMapper.getUserById(user.getId());
        assertNotNull(persisted);
        return persisted;
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
        SecurityContextHolder.clearContext();
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
}
