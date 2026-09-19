package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
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
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.webauthn.WebauthnUserEntityRow;

/**
 * Break-glass recovery tokens are bound to one account and spent on first use (#1532).
 *
 * <p>Each ceremony gets its own token, whose digest is computed over the recovering account's id
 * and configured on the live properties bean just before the request. That mirrors the operator
 * runbook: one token per account, one digest configured at a time.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrivilegedMfaRecoveryTokenBindingIntegrationTest {
    private static final String PASSWORD = "correct-horse-battery-staple";

    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.security.privileged-mfa.recovery-token-sha256",
                () -> sha256Hex("unused-startup-recovery-token".getBytes(StandardCharsets.UTF_8)));
        registry.add("connex.security.privileged-mfa.recovery-expires-at",
                () -> Instant.now().plus(Duration.ofMinutes(55)).toString());
        registry.add("connex.security.privileged-mfa.recovery-actor",
                () -> "integration-token-binding-operator");
    }

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private WebauthnUserEntityMapper userEntityMapper;
    @Autowired private WebauthnCredentialMapper credentialMapper;
    @Autowired private UserCredentialRepository userCredentials;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @MockitoSpyBean private UserMapper userMapper;

    private MockMvc mockMvc;
    private String startupDigest;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        startupDigest = privilegedMfaProperties.getRecoveryTokenSha256();
    }

    @AfterEach
    void restore() {
        privilegedMfaProperties.setRecoveryTokenSha256(startupDigest);
        SecurityContextHolder.clearContext();
    }

    /**
     * A token that already completed a ceremony is spent: replaying it later, from a new session,
     * is refused and leaves the replacement passkey, the epoch and the ledger untouched.
     */
    @Test
    void replayingARedeemedTokenInANewSessionIsRefusedAndRemovesNothing() throws Exception {
        User account = privilegedPasswordAccount();
        String token = issueRecoveryToken(account);
        recover(authenticatedSession(account), token).andExpect(status().isOk());
        enrollPasskey(account);
        Integer epochAfterRecovery = userMapper.currentSessionEpoch(account.getId());
        assertNotNull(epochAfterRecovery);
        User current = userMapper.getUserById(account.getId());
        assertNotNull(current);

        recover(authenticatedSession(current), token).andExpect(status().isForbidden());

        assertTrue(credentialMapper.existsByUserId(account.getId()));
        assertEquals(epochAfterRecovery, userMapper.currentSessionEpoch(account.getId()));
        assertEquals(1, redemptionsFor(account));
        assertEquals(
                sha256Hex(HexFormat.of().parseHex(privilegedMfaProperties.getRecoveryTokenSha256())),
                jdbcTemplate.queryForObject(
                        "SELECT token_digest FROM privileged_mfa_recovery_redemption WHERE user_id = ?",
                        String.class, account.getId()));
    }

    /**
     * Another account's token is refused and grants no epoch-restamp handoff, so that account's
     * first enrollment is still held behind the emailed confirmation. The token remains valid for
     * the account it was issued to.
     */
    @Test
    void anotherAccountsTokenIsRefusedAndGrantsNoBootstrapBypass() throws Exception {
        User issuedFor = privilegedPasswordAccount();
        User attacker = privilegedPasswordAccount();
        String token = issueRecoveryToken(issuedFor);
        MockHttpSession attackerSession = authenticatedSession(attacker);
        int attackerEpoch = attacker.getSessionEpoch();

        recover(attackerSession, token).andExpect(status().isForbidden());

        assertNull(userMapper.epochRestampGrant(attacker.getId()));
        assertEquals(attackerEpoch, userMapper.currentSessionEpoch(attacker.getId()));
        assertEquals(0, redemptionsFor(attacker));
        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(attackerSession)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        recover(authenticatedSession(issuedFor), token).andExpect(status().isOk());
        assertEquals(1, redemptionsFor(issuedFor));
    }

    /**
     * The ledger row is written inside the recovery transaction. A ceremony that fails after the
     * token check rolls it back, so the token is not burned and the retry succeeds.
     */
    @Test
    void aCeremonyThatFailsAfterTheTokenCheckDoesNotSpendTheToken() throws Exception {
        User account = privilegedPasswordAccount();
        String token = issueRecoveryToken(account);
        MockHttpSession session = authenticatedSession(account);
        doReturn(0).when(userMapper).bumpSessionEpoch(account.getId());

        recover(session, token).andExpect(status().isConflict());

        assertEquals(0, redemptionsFor(account));
        assertNull(userMapper.epochRestampGrant(account.getId()));
        reset(userMapper);

        recover(session, token).andExpect(status().isOk());
        assertEquals(1, redemptionsFor(account));
        assertNotNull(userMapper.epochRestampGrant(account.getId()));
    }

    private ResultActions recover(MockHttpSession session, String token) throws Exception {
        return mockMvc.perform(post("/api/auth/webauthn/recover")
                .session(session)
                .with(csrf().asHeader())
                .contentType("application/json")
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"recoveryToken\":\"" + token + "\"}"));
    }

    /**
     * Issues a fresh token for exactly one account, as the operator runbook does.
     *
     * @param account the account the token may recover
     * @return the raw token handed to the account holder
     */
    private String issueRecoveryToken(User account) {
        String token = UUID.randomUUID().toString();
        privilegedMfaProperties.setRecoveryTokenSha256(sha256Hex(
                ("connex-privileged-mfa-recovery:v1:" + account.getId() + ":" + token)
                        .getBytes(StandardCharsets.UTF_8)));
        return token;
    }

    private int redemptionsFor(User account) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM privileged_mfa_recovery_redemption WHERE user_id = ?",
                Integer.class, account.getId());
        assertNotNull(count);
        return count;
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

    private User privilegedPasswordAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("rtb_" + suffix);
        user.setDisplayName("Token Binding " + suffix);
        user.setEmail("rtb_" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        jdbcTemplate.update(
                "INSERT INTO organization (name, slug) VALUES (?, ?)", "Org " + suffix, "rtb-org-" + suffix);
        Integer orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug = ?", Integer.class, "rtb-org-" + suffix);
        assertNotNull(orgId);
        jdbcTemplate.update(
                "INSERT INTO workspace (name, slug, org_id) VALUES (?, ?, ?)",
                "Workspace " + suffix, "rtb-ws-" + suffix, orgId);
        Integer workspaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE slug = ?", Integer.class, "rtb-ws-" + suffix);
        assertNotNull(workspaceId);
        workspaceMapper.addMember(workspaceId, user.getId(), "owner");
        User persisted = userMapper.getUserById(user.getId());
        assertNotNull(persisted);
        assertFalse(credentialMapper.existsByUserId(persisted.getId()));
        return persisted;
    }

    private void enrollPasskey(User account) {
        WebauthnUserEntityRow entity = userEntityMapper.findByUserId(account.getId());
        Bytes handle;
        if (entity == null) {
            handle = Bytes.random();
            WebauthnUserEntityRow created = new WebauthnUserEntityRow();
            created.setId(handle.toBase64UrlString());
            created.setUserId(account.getId());
            created.setName(account.getUsername());
            created.setDisplayName(account.getDisplayName());
            userEntityMapper.insert(created);
        } else {
            handle = Bytes.fromBase64(entity.getId());
        }
        userCredentials.save(ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.random())
                .userEntityUserId(handle)
                .publicKey(new ImmutablePublicKeyCose(new byte[] {9, 9, 9}))
                .signatureCount(0)
                .created(Instant.now())
                .build());
        assertTrue(credentialMapper.existsByUserId(account.getId()));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
