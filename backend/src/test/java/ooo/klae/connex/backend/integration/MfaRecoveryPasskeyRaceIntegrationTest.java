package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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

    private MockHttpServletRequest establishSession(User verifiedPrincipal) {
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext());
        request.setSession(new MockHttpSession(context.getServletContext()));
        authService.establishAuthenticatedSession(
                verifiedPrincipal, request, new MockHttpServletResponse());
        return request;
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
