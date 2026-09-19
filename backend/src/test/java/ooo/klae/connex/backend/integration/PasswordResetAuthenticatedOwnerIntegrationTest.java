package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.exceptions.BreachedPasswordCheckUnavailableException;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.password.BreachedPasswordLookup;
import ooo.klae.connex.backend.password.BreachedPasswordSourceUnavailableException;
import ooo.klae.connex.backend.password.BreachedPasswordUnavailableReason;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Redeems a reset link while signed in as the account owner with the breach corpus unanswered.
 *
 * <p>The reset holds the owner's {@code app_user} row exclusively from {@code lockById} to commit,
 * and an independent audit append re-locks the actor's row shared on a second connection. With the
 * owner as actor, an append of the breached-password decision inside that window waits on the
 * reset's own lock until the InnoDB timeout. These tests bound every reset well below that timeout
 * and pin where the decision lands: {@code fail_open} commits atomically with the new credential,
 * and {@code fail_closed} is committed after the rollback that refuses the reset.
 */
@SpringBootTest
class PasswordResetAuthenticatedOwnerIntegrationTest {

    private static final String OLD_PASSWORD = "Owner-Old-Pw1!";
    private static final String NEW_PASSWORD = "OwnerResetPw2!";
    private static final String DECISION_ACTION = "auth.password.breach_check_unavailable";
    private static final Duration WELL_BELOW_LOCK_WAIT_TIMEOUT = Duration.ofSeconds(20);

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private BreachedPasswordLookup breachedPasswordLookup;
    @MockitoSpyBean private AuditService auditService;

    private MockMvc mockMvc;
    private User owner;
    private MockHttpSession ownerSession;
    private Cookie binding;
    private Cookie grant;
    private String tokenHash;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
        owner = newUser();
        ownerSession = logIn(owner);
        binding = bindBrowser(ownerSession);
        String rawToken = OneTimeTokenDigest.generate();
        tokenHash = OneTimeTokenDigest.sha256(rawToken);
        passwordResetTokenMapper.insert(
            owner.getId(), tokenHash, "198.51.100.71", 30, userMapper.currentSessionEpoch(owner.getId()));
        grant = exchange(rawToken);
    }

    @Test
    void failOpenResetByTheSignedInOwnerCommitsTheCredentialAndItsDecisionPromptly() throws Exception {
        corpusUnavailable(BreachedPasswordUnavailableReason.TIMEOUT);

        resetPromptly(200);

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, passwordHash()));
        assertEquals(List.of("fail_open|timeout|" + owner.getId()), decisions());
        assertEquals(1, completedResets());
        assertFalse(passwordResetTokenMapper.existsExchangedRedeemableByHash(tokenHash));
    }

    @Test
    void failClosedResetByTheSignedInOwnerIsRefusedPromptlyAndAuditedAfterRollback() throws Exception {
        corpusUnavailable(BreachedPasswordUnavailableReason.CAPACITY);

        MvcResult refused = resetPromptly(503);

        jsonPath("$.code").value(BreachedPasswordCheckUnavailableException.CODE).match(refused);
        jsonPath("$.newPassword").value(BreachedPasswordCheckUnavailableException.MESSAGE).match(refused);
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, passwordHash()));
        assertEquals(List.of("fail_closed|capacity|" + owner.getId()), decisions());
        assertEquals(0, completedResets());
        assertGrantStillRedeemable();
    }

    @Test
    void failedFailOpenDecisionAppendRollsTheCredentialBack() throws Exception {
        corpusUnavailable(BreachedPasswordUnavailableReason.TIMEOUT);
        doThrow(new IllegalStateException("audit append refused")).when(auditService).recordStrictScoped(
            eq(DECISION_ACTION), eq("user"), eq(owner.getId()), isNull(), isNull(), anyString(),
            anyString(), any());

        resetPromptly(409);

        assertTrue(passwordEncoder.matches(OLD_PASSWORD, passwordHash()));
        assertEquals(List.of(), decisions());
        assertEquals(0, completedResets());
        assertGrantStillRedeemable();
    }

    private void corpusUnavailable(BreachedPasswordUnavailableReason reason) {
        when(breachedPasswordLookup.isBreached(anyString()))
            .thenThrow(new BreachedPasswordSourceUnavailableException(reason));
    }

    private MvcResult resetPromptly(int expectedStatus) {
        return assertTimeoutPreemptively(WELL_BELOW_LOCK_WAIT_TIMEOUT, () -> mockMvc.perform(
                    post("/api/auth/reset-password")
                        .session(ownerSession)
                        .cookie(binding, grant)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn(),
            "the reset must not wait on its own account lock to audit the breach decision");
    }

    private void assertGrantStillRedeemable() throws Exception {
        assertTrue(passwordResetTokenMapper.existsExchangedRedeemableByHash(tokenHash));
        mockMvc.perform(get("/api/auth/reset-password/validate")
                .session(ownerSession)
                .cookie(binding, grant))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.valid").value(true));
    }

    private List<String> decisions() {
        return jdbcTemplate.queryForList("""
            SELECT CONCAT(JSON_UNQUOTE(JSON_EXTRACT(changes, '$.decision')), '|',
                JSON_UNQUOTE(JSON_EXTRACT(changes, '$.reason')), '|', COALESCE(actor_id, 'none'))
            FROM audit_log
            WHERE action = ? AND entity_type = 'user' AND entity_id = ?
            ORDER BY id
            """, String.class, DECISION_ACTION, owner.getId());
    }

    private int completedResets() {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE action = 'auth.password_reset_completed' AND entity_type = 'user' AND entity_id = ?
            """, Integer.class, owner.getId());
        assertNotNull(count);
        return count;
    }

    private String passwordHash() {
        User stored = userMapper.getUserById(owner.getId());
        assertNotNull(stored);
        return stored.getPasswordHash();
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("reset_owner_" + suffix);
        user.setDisplayName("Reset Owner " + suffix);
        user.setEmail("reset_owner_" + suffix + "@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(OLD_PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private MockHttpSession logIn(User user) throws Exception {
        MvcResult loggedIn = mockMvc.perform(post("/api/auth/login")
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + OLD_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        if (!(loggedIn.getRequest().getSession(false) instanceof MockHttpSession session)) {
            throw new IllegalStateException("Expected an authenticated session");
        }
        return session;
    }

    private Cookie bindBrowser(MockHttpSession session) throws Exception {
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
        return responseCookie(bootstrap, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE);
    }

    private Cookie exchange(String rawToken) throws Exception {
        MvcResult exchanged = mockMvc.perform(post("/api/auth/reset-password/exchange")
                .session(ownerSession)
                .cookie(binding)
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + rawToken + "\"}"))
            .andExpect(status().isSeeOther())
            .andReturn();
        return responseCookie(exchanged, OneTimeLinkFlowCookie.PASSWORD_RESET);
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "="))
            .findFirst()
            .orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }
}
