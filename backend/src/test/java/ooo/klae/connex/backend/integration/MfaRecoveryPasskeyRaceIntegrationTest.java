package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WebauthnCredentialMapper;
import ooo.klae.connex.backend.mappers.WebauthnUserEntityMapper;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.MfaRecoveryService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import ooo.klae.connex.backend.webauthn.WebauthnUserEntityRow;

/**
 * Interleaves passkey recovery with the login and registration windows that cross its account
 * lock and Spring Session commit boundary (#1491).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MfaRecoveryPasskeyRaceIntegrationTest {
    private static final String RECOVERY_TOKEN = "mfa-recovery-race-token";
    private static final Duration RECOVERY_WINDOW = Duration.ofMinutes(55);

    /**
     * Resolves the operator recovery window when the context starts rather than when this class is
     * loaded. The break-glass token is rejected once its expiry has passed, and a cold schema
     * migration can put a load-time constant well behind the clock before the context refreshes.
     */
    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.security.privileged-mfa.recovery-token-sha256",
                () -> sha256Hex(RECOVERY_TOKEN));
        registry.add("connex.security.privileged-mfa.recovery-expires-at",
                () -> Instant.now().plus(RECOVERY_WINDOW).toString());
        registry.add("connex.security.privileged-mfa.recovery-actor",
                () -> "integration-security-operator");
    }

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private AuthService authService;
    @Autowired private MfaRecoveryService mfaRecoveryService;
    @Autowired private SessionSecurityService sessionSecurityService;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private UserMapper userMapper;
    @Autowired private WebauthnUserEntityMapper userEntityMapper;
    @Autowired private WebauthnCredentialMapper credentialMapper;
    @Autowired private UserCredentialRepository userCredentials;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @Autowired private SessionRegistry sessionRegistry;
    @MockitoSpyBean private SpringSessionMapper springSessionMapper;

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
     * Models the exact fail-open miss: the passkey principal is read before recovery, while its
     * authenticated session is established only after recovery has bumped and enumerated.
     */
    @Test
    void aRacedPasskeyLoginCannotEnrolAReplacement() throws Exception {
        User account = passwordlessAccount();
        enrollPasskey(account);
        User principalReadBeforeRecovery = userMapper.getUserByUsername(account.getUsername());
        assertNotNull(principalReadBeforeRecovery);
        assertNotNull(principalReadBeforeRecovery.getSessionEpoch());
        int preRecoveryEpoch = principalReadBeforeRecovery.getSessionEpoch();
        MockHttpServletRequest ceremonyRequest = establishSession(account);

        int recoveryEpoch = recover(ceremonyRequest, account);

        MockHttpServletRequest racedRequest = establishSession(principalReadBeforeRecovery);
        MockHttpSession racedSession = (MockHttpSession) racedRequest.getSession(false);
        assertEquals(preRecoveryEpoch, sessionSecurityService.sessionEpoch(racedSession));
        assertEquals(recoveryEpoch, userMapper.currentSessionEpoch(account.getId()));
        SecurityContextHolder.clearContext();

        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(ceremonySession)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/webauthn/register/options")
                        .session(racedSession)
                        .with(csrf().asHeader())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCeremonySessionSurvivesRecovery() throws Exception {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);

        int recoveryEpoch = recover(ceremonyRequest, account);
        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        assertEquals(recoveryEpoch, sessionSecurityService.sessionEpoch(ceremonySession));
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/auth/me").session(ceremonySession))
                .andExpect(status().isOk());
    }

    @Test
    void aLostRestampIsRepairedByTheDurableGrant() throws Exception {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);

        int recoveryEpoch = recover(ceremonyRequest, account);
        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        ceremonySession.removeAttribute(SessionSecurityService.SESSION_EPOCH_ATTR);
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/auth/me").session(ceremonySession))
                .andExpect(status().isOk());

        assertEquals(recoveryEpoch, sessionSecurityService.sessionEpoch(ceremonySession));
        assertNotNull(userMapper.epochRestampGrant(account.getId()));
    }

    /**
     * Rotation after the handoff is granted: the stored row keeps its primary id, so the rotated
     * ceremony session still repairs even though it now presents a different logical id.
     */
    @Test
    void theHandoffSurvivesARotationAfterTheGrant() throws Exception {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);

        int recoveryEpoch = recover(ceremonyRequest, account);

        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        MockHttpSession rotatedSession = rotateStoredSession(ceremonySession);
        assertNotEquals(ceremonySession.getId(), rotatedSession.getId());
        rotatedSession.removeAttribute(SessionSecurityService.SESSION_EPOCH_ATTR);
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/auth/me").session(rotatedSession))
                .andExpect(status().isOk());
        assertEquals(recoveryEpoch, sessionSecurityService.sessionEpoch(rotatedSession));
    }

    /**
     * Rotation that commits before recovery takes the account lock. A request's view of its own
     * session id is fixed for its lifetime, so recovery is holding a stale id it cannot detect by
     * re-reading. Resolving the row identity under the lock refuses the ceremony outright, leaving
     * the credentials in place — the account is never left with nothing to sign in with.
     */
    @Test
    void aRotationCommittedBeforeTheLockIsRefusedWithCredentialsIntact() {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);
        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        int epochBeforeRecovery = userMapper.currentSessionEpoch(account.getId());

        rotateStoredSession(ceremonySession);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities()));
        PasskeyRecoveryRequest request = new PasskeyRecoveryRequest();
        request.setRecoveryToken(RECOVERY_TOKEN);

        assertThrows(ForbiddenException.class,
                () -> mfaRecoveryService.recover(request, ceremonyRequest));

        assertTrue(credentialMapper.existsByUserId(account.getId()));
        assertEquals(epochBeforeRecovery, userMapper.currentSessionEpoch(account.getId()));
        assertNull(userMapper.epochRestampGrant(account.getId()));
    }

    /**
     * The interval the primary-id binding alone does not cover: a fixation rotation commits in
     * Spring Session's own transaction after recovery resolved the ceremony row but before it
     * enumerates. Excluding by the captured logical id would expire the very row the handoff names,
     * leaving a credential-less account with nothing live to enrol from.
     */
    @Test
    void aRotationBetweenLookupAndEnumerationDoesNotExpireTheCeremonyRow() {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);
        MockHttpSession ceremonySession = (MockHttpSession) ceremonyRequest.getSession(false);
        MockHttpSession otherSession = storeBackedSession(account);
        String ceremonySessionId = ceremonySession.getId();
        String ceremonyPrimaryId = springSessionMapper.primaryIdBySessionId(ceremonySessionId);
        assertNotNull(ceremonyPrimaryId);

        AtomicReference<String> rotatedId = new AtomicReference<>();
        doAnswer(invocation -> {
            if (rotatedId.get() == null) {
                rotatedId.set(rotateStored(sessionRepository, ceremonySessionId));
            }
            return ceremonyPrimaryId;
        }).when(springSessionMapper).primaryIdBySessionId(ceremonySessionId);

        recover(ceremonyRequest, account);

        assertNotNull(rotatedId.get());
        assertNotEquals(ceremonySessionId, rotatedId.get());
        assertFalse(sessionRegistry.getSessionInformation(rotatedId.get()).isExpired());
        assertTrue(sessionRegistry.getSessionInformation(otherSession.getId()).isExpired());
    }

    @Test
    void theGrantCannotRepairAnotherSession() throws Exception {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);
        MockHttpServletRequest otherRequest = establishSession(
                userMapper.getUserByUsername(account.getUsername()));

        recover(ceremonyRequest, account);
        MockHttpSession otherSession = (MockHttpSession) otherRequest.getSession(false);
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/auth/me").session(otherSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRegistrationAdmittedBeforeRecoveryIsRefusedUnderTheLock() {
        User account = passwordlessAccount();
        enrollPasskey(account);
        MockHttpServletRequest ceremonyRequest = establishSession(account);
        Integer admittedEpoch = sessionSecurityService.sessionEpoch(
                ceremonyRequest.getSession(false));
        assertNotNull(admittedEpoch);

        recover(ceremonyRequest, account);

        assertThrows(ForbiddenException.class, () -> webAuthnService.finishRegistration(
                account.getId(),
                admittedEpoch,
                true,
                null,
                null,
                "replacement"));
    }

    private int recover(MockHttpServletRequest ceremonyRequest, User account) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities()));
        PasskeyRecoveryRequest request = new PasskeyRecoveryRequest();
        request.setRecoveryToken(RECOVERY_TOKEN);
        int epoch = mfaRecoveryService.recover(request, ceremonyRequest);
        sessionSecurityService.completeRecoveryStamp(ceremonyRequest, epoch);
        assertFalse(credentialMapper.existsByUserId(account.getId()));
        return epoch;
    }

    /**
     * A servlet session whose id is backed by a real row in the shared session store, so the
     * handoff's primary-id lookup resolves exactly as it does in production.
     */
    /**
     * A servlet session backed by a real store row that carries the account's security context, so
     * {@code AccountSessionIndexResolver} files it under the account and revocation can actually
     * enumerate it. A row without that context is invisible to enumeration and would make any
     * assertion about expiry vacuous.
     */
    private MockHttpSession storeBackedSession(User principal) {
        return new MockHttpSession(
                context.getServletContext(), createStored(sessionRepository, principal));
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

    private static <S extends Session> String rotateStored(
            SessionRepository<S> repository, String sessionId) {
        S stored = repository.findById(sessionId);
        assertNotNull(stored);
        String rotated = stored.changeSessionId();
        repository.save(stored);
        return rotated;
    }

    /**
     * Models Spring Session's fixation rotation: the stored row keeps its primary id and its
     * attributes while the logical session id the client presents is replaced.
     */
    private MockHttpSession rotateStoredSession(MockHttpSession session) {
        String rotated = rotateStored(sessionRepository, session.getId());
        MockHttpSession rotatedSession = new MockHttpSession(context.getServletContext(), rotated);
        copyAttributes(session, rotatedSession);
        return rotatedSession;
    }

    /**
     * Establishes a session and binds the result to a real row in the shared session store, as the
     * production ceremony does. The login rotates the servlet session id, and only the id it ends
     * on is the one Spring Session persists.
     */
    private MockHttpServletRequest establishSession(User verifiedPrincipal) {
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        request.setSession(new MockHttpSession(context.getServletContext()));
        authService.establishAuthenticatedSession(
                verifiedPrincipal, request, new MockHttpServletResponse());
        MockHttpSession established = (MockHttpSession) request.getSession(false);
        MockHttpSession stored = storeBackedSession(verifiedPrincipal);
        copyAttributes(established, stored);
        request.setSession(stored);
        return request;
    }

    private static void copyAttributes(MockHttpSession from, MockHttpSession to) {
        Collections.list(from.getAttributeNames())
                .forEach(name -> to.setAttribute(name, from.getAttribute(name)));
    }

    private User passwordlessAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("mfa_race_" + suffix);
        user.setDisplayName("MFA Race " + suffix);
        user.setEmail("mfa_race_" + suffix + "@example.com");
        user.setTimezone("UTC");
        userMapper.insert(user);
        User persisted = userMapper.getUserById(user.getId());
        assertNotNull(persisted);
        assertNotNull(persisted.getSessionEpoch());
        assertEquals(0, persisted.getSessionEpoch());
        return persisted;
    }

    private void enrollPasskey(User account) {
        Bytes handle = Bytes.random();
        WebauthnUserEntityRow entity = new WebauthnUserEntityRow();
        entity.setId(handle.toBase64UrlString());
        entity.setUserId(account.getId());
        entity.setName(account.getUsername());
        entity.setDisplayName(account.getDisplayName());
        userEntityMapper.insert(entity);
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

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
