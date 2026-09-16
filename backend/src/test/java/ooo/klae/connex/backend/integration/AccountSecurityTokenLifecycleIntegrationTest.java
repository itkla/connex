package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.EmailChangeToken;
import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.OneTimeLinkFlowCookie;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.EmailChangeEmailService;
import ooo.klae.connex.backend.services.EmailChangeService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService;
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.Purpose;
import ooo.klae.connex.backend.services.PasswordResetEmailService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Exercises reciprocal recovery invalidation, account-lock races, password-proof throttling, and
 * the audit placement that keeps the shared password confirmation usable under the account lock.
 */
@SpringBootTest
class AccountSecurityTokenLifecycleIntegrationTest {

    private static final String PASSWORD = "Original-L07-Pw1!";
    private static final String NEW_PASSWORD = "RecoveredL07Pw2!";
    private static final String CLIENT_IP = "198.51.100.107";
    private static final String CONFIRMATION_IP = "203.0.113.107";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private EmailChangeTokenMapper emailChangeTokenMapper;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private EmailChangeService emailChangeService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private AuthService authService;
    @Autowired private OneTimeLinkFlowService oneTimeLinkFlowService;
    @MockitoSpyBean private AuditService auditService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private UserMapper userMapper;
    @MockitoSpyBean private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailChangeEmailService emailDelivery;
    @MockitoBean private PasswordResetEmailService resetDelivery;
    @Value("${connex.login.max-failures-per-user:10}") private int failureLimit;

    private MockMvc mockMvc;
    private User user;
    private Workspace workspace;
    private MockHttpSession authenticatedSession;
    private String newEmail;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Token lifecycle " + suffix);
        organization.setSlug("token-lifecycle-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Token lifecycle " + suffix);
        workspace.setSlug("token-lifecycle-" + suffix);
        workspaceMapper.insert(workspace);
        user = new User();
        user.setUsername("token_lifecycle_" + suffix);
        user.setDisplayName("Token lifecycle " + suffix);
        user.setEmail("original_" + suffix + "@example.com");
        user.setEmailVerified(true);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        newEmail = "replacement_" + suffix + "@example.com";
        logIn();
    }

    private void logIn() throws Exception {
        MvcResult loggedIn = mockMvc.perform(post("/api/auth/login")
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk()).andReturn();
        authenticatedSession = session(loggedIn);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordResetRefusesRetainedEmailChangeAtExchangeAndConfirm(boolean alreadyExchanged) throws Exception {
        String emailToken = requestEmailChange();
        Browser attacker = browser();
        Cookie grant = alreadyExchanged ? exchangeEmail(emailToken, attacker) : null;
        String resetToken = requestReset();
        Browser recovery = browser();
        Cookie resetGrant = exchangeReset(resetToken, recovery);
        resetPassword(recovery, resetGrant, 200);

        exchange("/api/auth/email-change/exchange", emailToken, browser(), 400);
        confirmEmail(attacker, grant, 400);
        assertThrows(BadRequestException.class,
            () -> emailChangeService.confirmChangeByHash(OneTimeTokenDigest.sha256(emailToken)));
        assertFalse(emailChangeService.validateToken(emailToken));
        assertEquals(user.getEmail(), userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emailChangeRefusesOldMailboxResetAtExchangeAndConfirm(boolean alreadyExchanged) throws Exception {
        String resetToken = requestReset();
        Browser oldMailbox = browser();
        Cookie resetGrant = alreadyExchanged ? exchangeReset(resetToken, oldMailbox) : null;
        String emailToken = requestEmailChange();
        Browser newMailbox = browser();
        confirmEmail(newMailbox, exchangeEmail(emailToken, newMailbox), 200);

        exchange("/api/auth/reset-password/exchange", resetToken, browser(), 400);
        resetPassword(oldMailbox, resetGrant, 400);
        assertThrows(BadRequestException.class,
            () -> passwordResetService.resetPasswordByHash(OneTimeTokenDigest.sha256(resetToken), NEW_PASSWORD));
        assertFalse(passwordResetService.validateToken(resetToken));
        assertEquals(newEmail, userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void epochChangeRefusesOldTokensAndAllowsNewTokens(boolean emailChange, boolean alreadyExchanged) throws Exception {
        String token = emailChange ? requestEmailChange() : requestReset();
        String tokenHash = OneTimeTokenDigest.sha256(token);
        Integer generation = userMapper.currentSessionEpoch(user.getId());
        assertNotNull(generation);
        assertEquals(generation, tokenGeneration(tokenHash, emailChange));
        Browser holder = browser();
        Cookie grant = alreadyExchanged
                ? emailChange ? exchangeEmail(token, holder) : exchangeReset(token, holder)
                : null;

        assertEquals(1, userMapper.bumpSessionEpoch(user.getId()));

        String exchangePath = emailChange ? "/api/auth/email-change/exchange" : "/api/auth/reset-password/exchange";
        exchange(exchangePath, token, holder, 400);
        exchange(exchangePath, token, browser(), 400);
        if (emailChange) {
            confirmEmail(holder, grant, 400);
            assertThrows(BadRequestException.class, () -> emailChangeService.confirmChangeByHash(tokenHash));
            assertFalse(emailChangeService.validateToken(token));
            assertFalse(emailChangeService.validateExchangedTokenHash(tokenHash));
        } else {
            resetPassword(holder, grant, 400);
            assertThrows(BadRequestException.class,
                    () -> passwordResetService.resetPasswordByHash(tokenHash, NEW_PASSWORD));
            assertFalse(passwordResetService.validateToken(token));
            assertFalse(passwordResetService.validateExchangedTokenHash(tokenHash));
        }
        assertEquals(user.getEmail(), userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));

        logIn();
        clearInvocations(emailDelivery, resetDelivery);
        String freshToken = emailChange ? requestEmailChange() : requestReset();
        String freshHash = OneTimeTokenDigest.sha256(freshToken);
        assertEquals(generation + 1, tokenGeneration(freshHash, emailChange));
        Browser freshHolder = browser();
        if (emailChange) {
            confirmEmail(freshHolder, exchangeEmail(freshToken, freshHolder), 200);
            assertEquals(newEmail, userMapper.getUserById(user.getId()).getEmail());
        } else {
            resetPassword(freshHolder, exchangeReset(freshToken, freshHolder), 200);
            assertTrue(passwordEncoder.matches(NEW_PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
        }
    }

    @ParameterizedTest
    @CsvSource({"true, exchange", "true, retry", "true, confirm",
            "false, exchange", "false, retry", "false, confirm"})
    void epochChangeDuringAccountLockWaitRefusesToken(boolean emailChange, String operation) throws Exception {
        String token = emailChange ? requestEmailChange() : requestReset();
        String tokenHash = OneTimeTokenDigest.sha256(token);
        String owner = OneTimeTokenDigest.sha256("generation-race-owner");
        if (!operation.equals("exchange")) {
            if (emailChange) {
                emailChangeService.exchangeToken(token, owner);
            } else {
                passwordResetService.exchangeToken(token, owner);
            }
        }
        Runnable redeem = () -> {
            if (operation.equals("confirm")) {
                if (emailChange) {
                    emailChangeService.confirmChangeByHash(tokenHash);
                } else {
                    passwordResetService.resetPasswordByHash(tokenHash, NEW_PASSWORD);
                }
            } else {
                TransactionTemplate exchangeTransaction = new TransactionTemplate(transactionManager);
                exchangeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                exchangeTransaction.executeWithoutResult(transaction -> {
                    if (emailChange) {
                        emailChangeService.exchangeToken(token, owner);
                    } else {
                        passwordResetService.exchangeToken(token, owner);
                    }
                });
            }
        };

        runBehindAccountLock(redeem, () -> assertEquals(1, userMapper.bumpSessionEpoch(user.getId())));

        assertEquals(user.getEmail(), userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void missingTokenGenerationIsRefusedAtExchangeAndConfirmation(boolean emailChange, boolean alreadyExchanged)
            throws Exception {
        String token = OneTimeTokenDigest.generate();
        String tokenHash = OneTimeTokenDigest.sha256(token);
        Browser holder = browser();
        MockHttpServletRequest legacyRequest = new MockHttpServletRequest();
        legacyRequest.setSession(holder.session());
        legacyRequest.setCookies(holder.binding());
        String owner = oneTimeLinkFlowService.exchangeOwnerHash(legacyRequest);
        if (emailChange) {
            emailChangeTokenMapper.insert(user.getId(), newEmail, tokenHash, CLIENT_IP, 30, null);
        } else {
            passwordResetTokenMapper.insert(user.getId(), tokenHash, CLIENT_IP, 30, null);
        }
        Cookie grant = null;
        if (alreadyExchanged) {
            assertEquals(1, emailChange ? emailChangeTokenMapper.claimExchange(tokenHash, owner)
                    : passwordResetTokenMapper.claimExchange(tokenHash, owner));
            Purpose purpose = emailChange ? Purpose.EMAIL_CHANGE : Purpose.PASSWORD_RESET;
            grant = new Cookie(emailChange ? OneTimeLinkFlowCookie.EMAIL_CHANGE : OneTimeLinkFlowCookie.PASSWORD_RESET,
                    oneTimeLinkFlowService.issue(legacyRequest, purpose, tokenHash).value());
        }
        assertEquals(1, userMapper.bumpSessionEpoch(user.getId()));

        String path = emailChange ? "/api/auth/email-change/exchange" : "/api/auth/reset-password/exchange";
        exchange(path, token, holder, 400);
        exchange(path, token, browser(), 400);
        if (emailChange) {
            confirmEmail(holder, grant, 400);
            assertThrows(BadRequestException.class, () -> emailChangeService.confirmChangeByHash(tokenHash));
            assertFalse(emailChangeService.validateToken(token));
        } else {
            resetPassword(holder, grant, 400);
            assertThrows(BadRequestException.class,
                    () -> passwordResetService.resetPasswordByHash(tokenHash, NEW_PASSWORD));
            assertFalse(passwordResetService.validateToken(token));
        }
        assertEquals(user.getEmail(), userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));

        logIn();
        String fresh = emailChange ? requestEmailChange() : requestReset();
        Browser freshHolder = browser();
        if (emailChange) {
            confirmEmail(freshHolder, exchangeEmail(fresh, freshHolder), 200);
            assertEquals(newEmail, userMapper.getUserById(user.getId()).getEmail());
        } else {
            resetPassword(freshHolder, exchangeReset(fresh, freshHolder), 200);
            assertTrue(passwordEncoder.matches(NEW_PASSWORD, userMapper.getUserById(user.getId()).getPasswordHash()));
        }
    }

    @Test
    void profileUpdateReadBeforeEmailConfirmationCannotRestoreTheOldMailbox() throws Exception {
        String emailToken = requestEmailChange();
        Browser newMailbox = browser();
        Cookie grant = exchangeEmail(emailToken, newMailbox);
        CountDownLatch atProfileWrite = new CountDownLatch(1);
        CountDownLatch resumeProfileWrite = new CountDownLatch(1);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            User profile = invocation.getArgument(0, User.class);
            atProfileWrite.countDown();
            await(resumeProfileWrite);
            return realUserMapper.update(profile);
        }).when(userMapper).update(any(User.class));

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> profileUpdate = executor.submit(() -> {
                try {
                    mockMvc.perform(put("/api/users/" + user.getId())
                            .session(authenticatedSession).header("X-Workspace-Id", workspace.getId())
                            .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + user.getUsername()
                                    + "\",\"displayName\":\"Updated profile\",\"email\":\""
                                    + user.getEmail() + "\",\"timezone\":\"UTC\"}"))
                        .andExpect(status().isOk());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
            try {
                await(atProfileWrite);
                confirmEmail(newMailbox, grant, 200);
            } finally {
                resumeProfileWrite.countDown();
            }
            profileUpdate.get(15, TimeUnit.SECONDS);
        }

        User updated = userMapper.getUserById(user.getId());
        assertNotNull(updated);
        assertEquals("Updated profile", updated.getDisplayName());
        assertEquals(newEmail, updated.getEmail());
        assertTrue(passwordEncoder.matches(PASSWORD, updated.getPasswordHash()));
        ArgumentCaptor<User> profile = ArgumentCaptor.forClass(User.class);
        verify(userMapper).update(profile.capture());
        assertNull(profile.getValue().getEmail());
        assertNull(profile.getValue().getPasswordHash());
    }

    private Integer tokenGeneration(String tokenHash, boolean emailChange) {
        if (emailChange) {
            EmailChangeToken token = emailChangeTokenMapper.findRedeemableByHash(tokenHash);
            assertNotNull(token);
            return token.getCredentialGeneration();
        }
        PasswordResetToken token = passwordResetTokenMapper.findRedeemableByHash(tokenHash);
        assertNotNull(token);
        return token.getCredentialGeneration();
    }

    @Test
    void emailChangePasswordGuessesThrottleBeforeComparisonAndAddressEvaluation() throws Exception {
        clearInvocations(passwordEncoder);
        for (int attempt = 0; attempt < failureLimit; attempt++) {
            requestEmail(user.getEmail(), "wrong-password", 403);
        }
        verify(passwordEncoder, times(failureLimit)).matches(eq("wrong-password"), anyString());
        clearInvocations(passwordEncoder);

        requestEmail(user.getEmail(), "wrong-password", 429);
        requestEmail(user.getEmail(), PASSWORD, 429);
        requestEmail(newEmail, PASSWORD, 429);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(emailDelivery, never()).sendVerificationEmail(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        assertEquals(0, emailChangeTokenMapper.countRecentByUser(user.getId(), 900));
        assertEquals(failureLimit, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE action = 'auth.password_confirmation' AND entity_id = ? AND outcome = 'failure'
            """, Integer.class, user.getId()));
        assertEquals(1, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE action = 'auth.password_confirmation_throttled' AND entity_id = ? AND outcome = 'failure'
            """, Integer.class, user.getId()));
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM audit_log WHERE entity_id = ?
              AND action LIKE 'auth.password_confirmation%'
              AND CONCAT_WS('|', target_label, summary, context, changes) LIKE '%wrong-password%'
            """, Integer.class, user.getId()));
    }

    @ParameterizedTest
    @CsvSource({"198.51.100.108, 203.0.113.108", "127.0.0.1, 10.0.0.7"})
    void emailChangePasswordBudgetSurvivesHttpRenameAndSourceChange(String originalIp, String newIp)
            throws Exception {
        clearInvocations(passwordEncoder);
        for (int attempt = 0; attempt < failureLimit; attempt++) {
            requestEmail(user.getEmail(), "wrong-password", originalIp, 403);
        }
        verify(passwordEncoder, times(failureLimit)).matches(eq("wrong-password"), anyString());
        String renamed = "renamed_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(put("/api/users/" + user.getId())
                .session(authenticatedSession).header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + renamed
                        + "\",\"displayName\":\"Renamed account\",\"email\":\""
                        + user.getEmail() + "\",\"timezone\":\"UTC\"}"))
            .andExpect(status().isOk());
        User updated = userMapper.getUserById(user.getId());
        assertNotNull(updated);
        assertEquals(renamed, updated.getUsername());
        clearInvocations(passwordEncoder);

        requestEmail(user.getEmail(), PASSWORD, newIp, 429);
        requestEmail(user.getEmail(), "wrong-password", newIp, 429);
        requestEmail(newEmail, PASSWORD, newIp, 429);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(emailDelivery, never()).sendVerificationEmail(any(), anyString(), anyString());
        assertEquals(0, emailChangeTokenMapper.countRecentByUser(user.getId(), 900));
    }

    /**
     * Pins the shared current-password confirmation as audit-free, because callers reach it while
     * already holding the account row exclusively. {@code MfaRecoveryService.recover} takes
     * {@code app_user FOR UPDATE} before the passkey-recovery bootstrap check, and an audit append
     * runs in an independent transaction that locks the actor's {@code app_user} row shared, so a
     * confirmation audit emitted from the shared step would wait on the caller's own lock until the
     * InnoDB timeout, drop the event, and pin two pooled connections per failed attempt. The
     * transaction here holds the same exclusive lock for the whole worker call, so a reintroduced
     * audit shows up both as the refused interaction and as a bounded-wait failure.
     */
    @Test
    void passwordConfirmationUnderTheAccountLockNeitherAuditsNorWaitsForTheLock() throws Exception {
        clearInvocations(auditService);
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                userMapper.lockById(user.getId());
                awaitConfirmation(executor.submit(this::confirmWrongPasswordAsAccountOwner));
            });
        }
        verify(auditService, never()).recordFailure(any(), any(), any(), any(), any(), any());
    }

    private void confirmWrongPasswordAsAccountOwner() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        try {
            assertThrows(BadCredentialsException.class, () -> authService.requireCurrentPassword(
                user.getId(), "wrong-password", new ResolvedClientIp(CONFIRMATION_IP, false)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void awaitConfirmation(Future<?> confirmation) {
        try {
            confirmation.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException(
                "the shared password confirmation must not wait on its caller's account lock", exception);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void losingConfirmationRechecksTokenAfterAccountLock(boolean resetWins) throws Exception {
        String emailToken = requestEmailChange();
        String resetToken = requestReset();
        String emailHash = emailChangeService.exchangeToken(emailToken, OneTimeTokenDigest.sha256("email-owner"));
        String resetHash = passwordResetService.exchangeToken(resetToken, OneTimeTokenDigest.sha256("reset-owner"));
        Runnable confirm = () -> emailChangeService.confirmChangeByHash(emailHash);
        Runnable reset = () -> passwordResetService.resetPasswordByHash(resetHash, NEW_PASSWORD);
        runBehindAccountLock(resetWins ? confirm : reset, resetWins ? reset : confirm);
        assertEquals(resetWins ? user.getEmail() : newEmail, userMapper.getUserById(user.getId()).getEmail());
        assertTrue(passwordEncoder.matches(resetWins ? NEW_PASSWORD : PASSWORD,
            userMapper.getUserById(user.getId()).getPasswordHash()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameOwnerExchangeRetryRechecksInvalidationAfterAccountLock(boolean resetWins) throws Exception {
        String emailToken = requestEmailChange();
        String resetToken = requestReset();
        String owner = OneTimeTokenDigest.sha256("same-owner");
        String emailHash = emailChangeService.exchangeToken(emailToken, owner);
        String resetHash = passwordResetService.exchangeToken(resetToken, owner);
        TransactionTemplate exchangeTransaction = new TransactionTemplate(transactionManager);
        exchangeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Runnable retry = () -> exchangeTransaction.executeWithoutResult(transaction -> {
            if (resetWins) {
                emailChangeService.exchangeToken(emailToken, owner);
            } else {
                passwordResetService.exchangeToken(resetToken, owner);
            }
        });
        Runnable winner = resetWins
            ? () -> passwordResetService.resetPasswordByHash(resetHash, NEW_PASSWORD)
            : () -> emailChangeService.confirmChangeByHash(emailHash);
        runBehindAccountLock(retry, winner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emailChangeIssuanceRejectsPasswordProofThatPredatesRecovery(boolean resetPassword) throws Exception {
        String resetToken = requestReset();
        String resetHash = passwordResetService.exchangeToken(resetToken, OneTimeTokenDigest.sha256("reset-owner"));
        CountDownLatch atAccountLock = signalWorkerAccountLock();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> request = new TransactionTemplate(transactionManager).execute(transaction -> {
                userMapper.lockById(user.getId());
                Future<?> pending = executor.submit(() -> {
                    try {
                        requestEmail(newEmail, PASSWORD, 403);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
                await(atAccountLock);
                if (resetPassword) {
                    passwordResetService.resetPasswordByHash(resetHash, NEW_PASSWORD);
                } else {
                    userMapper.bumpSessionEpoch(user.getId());
                }
                return pending;
            });
            assertNotNull(request);
            request.get(15, TimeUnit.SECONDS);
        }
        verify(emailDelivery, never()).sendVerificationEmail(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        assertEquals(0, emailChangeTokenMapper.countRecentByUser(user.getId(), 900));
    }

    @Test
    void resetIssuanceRechecksMailboxAfterAccountLock() throws Exception {
        String emailToken = requestEmailChange();
        String emailHash = emailChangeService.exchangeToken(emailToken, OneTimeTokenDigest.sha256("email-owner"));
        CountDownLatch atAccountLock = signalWorkerSharedAccountLock();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> request = new TransactionTemplate(transactionManager).execute(transaction -> {
                userMapper.lockById(user.getId());
                Future<?> pending = executor.submit(() -> passwordResetService.requestReset(user.getEmail(), CLIENT_IP));
                await(atAccountLock);
                emailChangeService.confirmChangeByHash(emailHash);
                return pending;
            });
            assertNotNull(request);
            request.get(15, TimeUnit.SECONDS);
        }
        verify(resetDelivery, never()).sendResetEmail(org.mockito.ArgumentMatchers.any(), anyString());
        assertEquals(newEmail, userMapper.getUserById(user.getId()).getEmail());
    }

    @Test
    void resetIssuanceAcceptsAccentFoldedLookupAndUsesStoredMailbox() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String storedEmail = "résumé_" + suffix + "@example.com";
        String submittedEmail = "resume_" + suffix + "@example.com";
        userMapper.updateEmail(user.getId(), storedEmail);
        User resolvedUser = userMapper.getUserByEmail(submittedEmail);
        assertNotNull(resolvedUser);
        assertEquals(user.getId(), resolvedUser.getId());

        passwordResetService.requestReset(submittedEmail, "127.0.0.1");

        ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(resetDelivery).sendResetEmail(recipient.capture(), token.capture());
        assertEquals(user.getId(), recipient.getValue().getId());
        assertEquals(storedEmail, recipient.getValue().getEmail());
        assertTrue(passwordResetService.validateToken(token.getValue()));
    }

    private void runBehindAccountLock(Runnable loser, Runnable winner) throws Exception {
        CountDownLatch atAccountLock = signalWorkerAccountLock();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> result = new TransactionTemplate(transactionManager).execute(transaction -> {
                userMapper.lockById(user.getId());
                Future<?> pending = executor.submit(() -> assertThrows(BadRequestException.class, loser::run));
                await(atAccountLock);
                winner.run();
                return pending;
            });
            assertNotNull(result);
            result.get(15, TimeUnit.SECONDS);
        }
    }

    private CountDownLatch signalWorkerAccountLock() {
        Thread testThread = Thread.currentThread();
        CountDownLatch atAccountLock = new CountDownLatch(1);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread() != testThread) {
                atAccountLock.countDown();
            }
            return realUserMapper.lockById(user.getId());
        }).when(userMapper).lockById(user.getId());
        return atAccountLock;
    }

    private CountDownLatch signalWorkerSharedAccountLock() {
        Thread testThread = Thread.currentThread();
        CountDownLatch atAccountLock = new CountDownLatch(1);
        UserMapper realUserMapper = sqlSessionTemplate.getMapper(UserMapper.class);
        doAnswer(invocation -> {
            if (Thread.currentThread() != testThread) {
                atAccountLock.countDown();
            }
            return realUserMapper.lockByIdForShare(user.getId());
        }).when(userMapper).lockByIdForShare(user.getId());
        return atAccountLock;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "request must reach the contended account-lock statement");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String requestEmailChange() throws Exception {
        requestEmail(newEmail, PASSWORD, 200);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailDelivery).sendVerificationEmail(org.mockito.ArgumentMatchers.any(), eq(newEmail), token.capture());
        return token.getValue();
    }

    private String requestReset() {
        passwordResetService.requestReset(user.getEmail(), "127.0.0.1");
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(resetDelivery).sendResetEmail(org.mockito.ArgumentMatchers.any(), token.capture());
        return token.getValue();
    }

    private void requestEmail(String address, String password, int expectedStatus) throws Exception {
        requestEmail(address, password, CLIENT_IP, expectedStatus);
    }

    private void requestEmail(String address, String password, String sourceIp, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/users/me/email-change")
                .session(authenticatedSession).header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()).with(request -> {
                    request.setRemoteAddr(sourceIp);
                    return request;
                }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + address + "\",\"currentPassword\":\"" + password + "\"}"))
            .andExpect(status().is(expectedStatus));
    }

    private Cookie exchangeEmail(String rawToken, Browser browser) throws Exception {
        return responseCookie(exchange("/api/auth/email-change/exchange", rawToken, browser, 303),
            OneTimeLinkFlowCookie.EMAIL_CHANGE);
    }

    private Cookie exchangeReset(String rawToken, Browser browser) throws Exception {
        return responseCookie(exchange("/api/auth/reset-password/exchange", rawToken, browser, 303),
            OneTimeLinkFlowCookie.PASSWORD_RESET);
    }

    private MvcResult exchange(String path, String token, Browser browser, int expectedStatus) throws Exception {
        return mockMvc.perform(post(path).session(browser.session()).cookie(browser.binding())
                .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
            .andExpect(status().is(expectedStatus)).andReturn();
    }

    private void confirmEmail(Browser browser, Cookie grant, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/email-change/confirm")
                .session(browser.session()).cookie(cookies(browser, grant)).with(csrf().asHeader()))
            .andExpect(status().is(expectedStatus));
    }

    private void resetPassword(Browser browser, Cookie grant, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                .session(browser.session()).cookie(cookies(browser, grant)).with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().is(expectedStatus));
    }

    private static Cookie[] cookies(Browser browser, Cookie grant) {
        return grant == null ? new Cookie[] {browser.binding()} : new Cookie[] {browser.binding(), grant};
    }

    private Browser browser() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new Browser(session(result), responseCookie(result, OneTimeLinkFlowService.BROWSER_BINDING_COOKIE));
    }

    private static MockHttpSession session(MvcResult result) {
        if (!(result.getRequest().getSession(false) instanceof MockHttpSession session)) {
            throw new IllegalStateException("Expected a browser session");
        }
        return session;
    }

    private static Cookie responseCookie(MvcResult result, String name) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    private record Browser(MockHttpSession session, Cookie binding) {
    }
}
