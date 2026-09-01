package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.MfaRecoveryService;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Proves over HTTP that a stolen password cannot mint the first passkey for an account that
 * administers other principals, and that the accounts which must stay self-service still can (#1506).
 *
 * <p>The confinement filter deliberately leaves the enrollment routes open, so the refusal has to
 * come from the controller behind them; a unit test could not show that the request reaches it.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrivilegedPasskeyBootstrapIntegrationTest {
    private static final String RECOVERY_TOKEN = "privileged-bootstrap-operator-token";
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(55);
    private static final String PASSWORD = "correct-horse-battery-staple";

    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.security.privileged-mfa.enforced", () -> "true");
        registry.add("connex.security.privileged-mfa.recovery-token-sha256",
                () -> sha256Hex(RECOVERY_TOKEN));
        registry.add("connex.security.privileged-mfa.recovery-expires-at",
                () -> Instant.now().plus(RECOVERY_WINDOW).toString());
        registry.add("connex.security.privileged-mfa.recovery-actor",
                () -> "integration-bootstrap-operator");
    }

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private MfaRecoveryService mfaRecoveryService;
    @Autowired private SessionSecurityService sessionSecurityService;
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
     * The defect this fixes: the password that logged the attacker in must not also enroll the
     * second factor that is supposed to contain a stolen password.
     */
    @Test
    void anAccountAdministeringOthersCannotBootstrapWithItsPasswordAlone() throws Exception {
        User admin = passwordAccount();
        int orgId = newOrganization();
        int workspace = newWorkspace(orgId);
        workspaceMapper.addMember(workspace, admin.getId(), "admin");
        workspaceMapper.addMember(workspace, passwordAccount().getId(), "member");
        MockHttpSession session = authenticatedSession(admin);

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        containsString("PRIVILEGED_PASSKEY_BOOTSTRAP_FORBIDDEN")));

        assertFalse(sessionSecurityService.hasFreshFirstPasskeyBootstrap(
                requestFor(session), admin.getId()));
    }

    /**
     * Every self-serve registration provisions its own workspace and owner membership in the same
     * transaction, so an account that administers nobody must keep enrolling with its password or
     * first enrollment becomes impossible without an operator.
     */
    @Test
    void anAccountAdministeringNobodyElseStillBootstrapsWithItsPassword() throws Exception {
        User founder = passwordAccount();
        int orgId = newOrganization();
        int workspace = newWorkspace(orgId);
        workspaceMapper.addMember(workspace, founder.getId(), "owner");
        assertTrue(userMapper.isPrivilegedAccount(founder.getId()));
        assertFalse(userMapper.holdsPrivilegeOverOtherAccounts(founder.getId()));

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(authenticatedSession(founder))
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * An ordinary member is outside the policy and must be unaffected.
     */
    @Test
    void anUnprivilegedAccountIsUnaffected() throws Exception {
        User member = passwordAccount();
        int orgId = newOrganization();
        int workspace = newWorkspace(orgId);
        workspaceMapper.addMember(workspace, member.getId(), "member");
        workspaceMapper.addMember(workspace, passwordAccount().getId(), "admin");

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(authenticatedSession(member))
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The stated escape path must actually work. Recovery previously refused an account with no
     * credential to remove, which would have made the refusal unrecoverable.
     */
    @Test
    void anOperatorRecoveryUnlocksANeverEnrolledAdministrator() throws Exception {
        User admin = passwordAccount();
        int orgId = newOrganization();
        int workspace = newWorkspace(orgId);
        workspaceMapper.addMember(workspace, admin.getId(), "admin");
        workspaceMapper.addMember(workspace, passwordAccount().getId(), "member");
        MockHttpServletRequest ceremony = ceremonyRequest(admin);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        PasskeyRecoveryRequest recovery = new PasskeyRecoveryRequest();
        recovery.setCurrentPassword(PASSWORD);
        recovery.setRecoveryToken(RECOVERY_TOKEN);
        int epoch = mfaRecoveryService.recover(recovery, ceremony);
        sessionSecurityService.completeRecoveryStamp(ceremony, epoch);
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session((MockHttpSession) ceremony.getSession(false))
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * The grant authorizes the ceremony session that earned it, not the account at large, so a
     * concurrent session cannot ride an operator's recovery.
     */
    @Test
    void aRecoveryGrantDoesNotAuthorizeAnotherSession() throws Exception {
        User admin = passwordAccount();
        int orgId = newOrganization();
        int workspace = newWorkspace(orgId);
        workspaceMapper.addMember(workspace, admin.getId(), "admin");
        workspaceMapper.addMember(workspace, passwordAccount().getId(), "member");
        MockHttpServletRequest ceremony = ceremonyRequest(admin);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        PasskeyRecoveryRequest recovery = new PasskeyRecoveryRequest();
        recovery.setCurrentPassword(PASSWORD);
        recovery.setRecoveryToken(RECOVERY_TOKEN);
        int epoch = mfaRecoveryService.recover(recovery, ceremony);
        SecurityContextHolder.clearContext();

        User refreshed = userMapper.getUserById(admin.getId());
        assertNotNull(refreshed);
        assertEquals(Integer.valueOf(epoch), refreshed.getSessionEpoch());
        MockHttpSession other = authenticatedSession(refreshed);

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(other)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequest requestFor(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        request.setSession(session);
        return request;
    }

    private MockHttpServletRequest ceremonyRequest(User account) {
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        request.setSession(new MockHttpSession(context.getServletContext()));
        authService.establishAuthenticatedSession(account, request, new MockHttpServletResponse());
        MockHttpSession established = (MockHttpSession) request.getSession(false);
        MockHttpSession stored = new MockHttpSession(
                context.getServletContext(), createStored(sessionRepository, account));
        Collections.list(established.getAttributeNames())
                .forEach(name -> stored.setAttribute(name, established.getAttribute(name)));
        request.setSession(stored);
        return request;
    }

    private MockHttpSession authenticatedSession(User account) {
        return (MockHttpSession) ceremonyRequest(account).getSession(false);
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

    private User passwordAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("boot_" + suffix);
        user.setDisplayName("Bootstrap " + suffix);
        user.setEmail("boot_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        User persisted = userMapper.getUserById(user.getId());
        assertNotNull(persisted);
        return persisted;
    }

    private int newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO organization (name, slug) VALUES (?, ?)", "Org " + suffix, "org-" + suffix);
        Integer id = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug = ?", Integer.class, "org-" + suffix);
        assertNotNull(id);
        return id;
    }

    private int newWorkspace(int orgId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO workspace (name, slug, org_id) VALUES (?, ?, ?)",
                "Workspace " + suffix, "ws-" + suffix, orgId);
        Integer id = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE slug = ?", Integer.class, "ws-" + suffix);
        assertNotNull(id);
        return id;
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
