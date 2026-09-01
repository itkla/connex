package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * The operator break-glass ceremony is the documented route back for a privileged account that
 * cannot satisfy the emailed first-enrollment confirmation — the instance cannot send mail, or the
 * mailbox is unreachable. Such an account has never enrolled, so recovery has no credential to
 * remove, and refusing it there left the route unexecutable.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NeverEnrolledBreakGlassIntegrationTest {
    private static final String RECOVERY_TOKEN = "never-enrolled-break-glass-token";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.security.privileged-mfa.recovery-token-sha256",
                () -> sha256Hex(RECOVERY_TOKEN));
        registry.add("connex.security.privileged-mfa.recovery-expires-at",
                () -> Instant.now().plus(Duration.ofMinutes(55)).toString());
        registry.add("connex.security.privileged-mfa.recovery-actor",
                () -> "integration-break-glass-operator");
    }

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private WebAuthnService webAuthnService;
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
     * The whole point: the ceremony must complete for an account with nothing to remove, and must
     * leave that session able to enroll the replacement.
     */
    @Test
    void breakGlassCompletesForAnAccountThatHasNeverEnrolled() throws Exception {
        User admin = privilegedPasswordAccount();
        assertFalse(webAuthnService.hasPasskey(admin.getId()));
        MockHttpSession session = authenticatedSession(admin);

        mockMvc.perform(post("/api/auth/webauthn/recover")
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\""
                                + RECOVERY_TOKEN + "\"}"))
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

        mockMvc.perform(post("/api/auth/webauthn/recover")
                        .session(authenticatedSession(admin))
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\"wrong\"}"))
                .andExpect(status().isForbidden());
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
