package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountMode;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionService;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenClient;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.connectedaccounts.UserProviderSecretCipher;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCaptureConnectionStateService;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mappers.NativeConnectSessionMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NativeConnectServiceTest {
    private static final String PROVIDER = "google";
    private static final String MANAGED_CLIENT_ID = "managed-client-id";

    @Autowired private NativeConnectService nativeConnectService;
    @Autowired private ProviderConnectionService providerConnectionService;
    @Autowired private ConnectedAccountProperties properties;
    @Autowired private MailProperties mailProperties;
    @Autowired private NativeConnectSessionMapper nativeSessionMapper;
    @Autowired private NativeConnectPkceSecretCipher pkceSecretCipher;
    @Autowired private ProviderConnectionMapper connectionMapper;
    @Autowired private UserProviderSecretCipher tokenCipher;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private ProviderTokenClient tokenClient;
    @MockitoBean private ProviderCaptureConnectionStateService captureConnectionStateService;

    private Workspace workspace;
    private User firstUser;
    private String originalAppBaseUrl;

    @BeforeEach
    void setUp() {
        workspace = workspaceMapper.getDefaultWorkspace();
        if (workspace == null) {
            workspace = new Workspace();
            workspace.setName("Native Connect Test Workspace");
            workspace.setSlug("native-connect-default");
            workspaceMapper.insert(workspace);
        }
        firstUser = newUser();
        originalAppBaseUrl = mailProperties.getAppBaseUrl();
        properties.getGoogle().setEnabled(true);
        properties.getGoogle().setMode(ConnectedAccountMode.MANAGED);
        properties.getGoogle().setClientId("operator-client-id");
        properties.getGoogle().setClientSecret("operator-client-secret");
        properties.getManaged().getGoogle().setClientId(MANAGED_CLIENT_ID);
        properties.getManaged().getGoogle().setClientSecret(null);
        authenticate(firstUser);
    }

    @AfterEach
    void tearDown() {
        properties.getGoogle().setEnabled(false);
        properties.getGoogle().setMode(ConnectedAccountMode.CUSTOM);
        properties.getGoogle().setClientId(null);
        properties.getGoogle().setClientSecret(null);
        properties.getManaged().getGoogle().setClientId(null);
        properties.getManaged().getGoogle().setClientSecret(null);
        properties.getMicrosoft().setEnabled(false);
        properties.getMicrosoft().setMode(ConnectedAccountMode.CUSTOM);
        properties.getMicrosoft().setClientId(null);
        properties.getMicrosoft().setClientSecret(null);
        properties.getManaged().getMicrosoft().setClientId(null);
        properties.getManaged().getMicrosoft().setClientSecret(null);
        mailProperties.setAppBaseUrl(originalAppBaseUrl);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
        reset(tokenClient, captureConnectionStateService);
    }

    @Test
    void pairingPrepareCompleteStoresEncryptedStableAccountIdentity() {
        stubTokens("authorization-code", "issuer-a", "subject-a", "a@example.test");

        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        assertBearerSecret(pairing.pairingCode());
        NativePrepareResponse prepared = prepare(pairing);
        assertBearerSecret(prepared.handoffTicket());
        String state = queryParameter(prepared.authorizeUrl(), "state");
        assertBearerSecret(state);
        NativeCompleteResponse completed = complete(
            prepared, "authorization-code", state);

        assertEquals("connected", completed.status());
        ProviderConnection connection = connection(firstUser);
        assertEquals("google:issuer-a:subject-a", connection.getProviderAccountId());
        assertEquals("a@example.test", connection.getProviderAccountEmail());
        assertTrue(tokenCipher.decryptTokenBundle(
            PROVIDER, firstUser.getId(), connection.getCredentialRef())
            .contains("refresh-authorization-code"));
        verify(captureConnectionStateService)
            .reconcile(firstUser.getId(), PROVIDER);
        authenticate(firstUser);
        NativePairingStatusResponse status =
            nativeConnectService.pairingStatus(PROVIDER);
        assertEquals("completed", status.status());
        assertNull(status.errorCode());
    }

    @Test
    void twoUsersRemainIsolatedAcrossHandoffPollingCancellationAndSecrets() {
        User secondUser = newUser();
        NativePairingResponse firstPairing = nativeConnectService.createPairing(PROVIDER);
        authenticate(secondUser);
        NativePairingResponse secondPairing = nativeConnectService.createPairing(PROVIDER);
        NativePrepareResponse firstPrepared = prepare(firstPairing);
        NativePrepareResponse secondPrepared = prepare(secondPairing);

        NativeConnectException mismatch = assertThrows(
            NativeConnectException.class,
            () -> complete(
                secondPrepared,
                "first-code",
                queryParameter(firstPrepared.authorizeUrl(), "state")));
        assertEquals("state_mismatch", mismatch.getCode());

        authenticate(secondUser);
        NativePairingStatusResponse secondStatus =
            nativeConnectService.pairingStatus(PROVIDER);
        assertEquals("failed", secondStatus.status());
        assertEquals("state_mismatch", secondStatus.errorCode());
        authenticate(firstUser);
        assertEquals(
            "prepared",
            nativeConnectService.pairingStatus(PROVIDER).status());

        authenticate(secondUser);
        NativePrepareResponse secondRetry = prepare(
            nativeConnectService.createPairing(PROVIDER));
        stubTokens("first-code", "issuer-a", "subject-a", "a@example.test");
        when(tokenClient.exchange(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, String> form = invocation.getArgument(1);
            String code = form.get("code");
            if ("first-code".equals(code)) {
                return tokenResponse("issuer-a", "subject-a", "a@example.test", code);
            }
            return tokenResponse("issuer-b", "subject-b", "b@example.test", code);
        });
        complete(
            firstPrepared,
            "first-code",
            queryParameter(firstPrepared.authorizeUrl(), "state"));
        complete(
            secondRetry,
            "second-code",
            queryParameter(secondRetry.authorizeUrl(), "state"));

        ProviderConnection firstConnection = connection(firstUser);
        ProviderConnection secondConnection = connection(secondUser);
        assertEquals("google:issuer-a:subject-a", firstConnection.getProviderAccountId());
        assertEquals("google:issuer-b:subject-b", secondConnection.getProviderAccountId());
        assertNotEquals(
            firstConnection.getCredentialRef(), secondConnection.getCredentialRef());
        assertThrows(
            RuntimeException.class,
            () -> tokenCipher.decryptTokenBundle(
                PROVIDER, secondUser.getId(), firstConnection.getCredentialRef()));
        assertThrows(
            RuntimeException.class,
            () -> tokenCipher.decryptTokenBundle(
                PROVIDER, firstUser.getId(), secondConnection.getCredentialRef()));

        authenticate(firstUser);
        nativeConnectService.createPairing(PROVIDER);
        authenticate(secondUser);
        nativeConnectService.cancelPairing(PROVIDER);
        authenticate(firstUser);
        assertEquals(
            "pending",
            nativeConnectService.pairingStatus(PROVIDER).status());
    }

    @Test
    void unrelatedAuthenticatedSessionCannotBecomeTheBearerHandoffActor() {
        User secondUser = newUser();
        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        authenticate(secondUser);
        NativePrepareResponse prepared = nativeConnectService.prepare(
            new NativePrepareRequest(
                pairing.pairingCode(),
                "http://127.0.0.1:49152/callback"));
        stubTokens("code", "issuer", "subject", "owner@example.test");
        authenticate(secondUser);

        nativeConnectService.complete(new NativeCompleteRequest(
            prepared.handoffTicket(),
            "code",
            queryParameter(prepared.authorizeUrl(), "state")));

        assertNotNull(connectionMapper.getByUserAndProvider(firstUser.getId(), PROVIDER));
        assertNull(connectionMapper.getByUserAndProvider(secondUser.getId(), PROVIDER));
        assertEquals(secondUser, SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log "
                + "WHERE action = 'user.connection.connect' "
                + "AND entity_type = 'user' AND entity_id = ? "
                + "AND workspace_id IS NULL AND org_id IS NULL AND actor_id IS NULL",
            Integer.class,
            firstUser.getId()));
    }

    @Test
    void oneUserProviderSessionsKeepIndependentVerifierSlots() {
        properties.getMicrosoft().setEnabled(true);
        properties.getMicrosoft().setMode(ConnectedAccountMode.MANAGED);
        properties.getManaged().getMicrosoft().setClientId("managed-microsoft-client-id");

        NativePrepareResponse googlePrepared = prepare(
            nativeConnectService.createPairing(PROVIDER));
        authenticate(firstUser);
        NativePrepareResponse microsoftPrepared = prepare(
            nativeConnectService.createPairing("microsoft"));
        NativeConnectSession googleSession = latest(firstUser, PROVIDER);
        NativeConnectSession microsoftSession = latest(firstUser, "microsoft");

        assertNotEquals(googleSession.getVerifierRef(), microsoftSession.getVerifierRef());
        String googleVerifier = pkceSecretCipher.read(
            PROVIDER, firstUser.getId(), googleSession.getVerifierRef());
        String microsoftVerifier = pkceSecretCipher.read(
            "microsoft", firstUser.getId(), microsoftSession.getVerifierRef());
        assertEquals(
            NativeConnectPkce.challenge(googleVerifier),
            queryParameter(googlePrepared.authorizeUrl(), "code_challenge"));
        assertEquals(
            NativeConnectPkce.challenge(microsoftVerifier),
            queryParameter(microsoftPrepared.authorizeUrl(), "code_challenge"));

        authenticate(firstUser);
        nativeConnectService.cancelPairing(PROVIDER);
        clearAuthentication();
        assertThrows(
            RuntimeException.class,
            () -> pkceSecretCipher.read(
                PROVIDER, firstUser.getId(), googleSession.getVerifierRef()));
        assertEquals(
            microsoftVerifier,
            pkceSecretCipher.read(
                "microsoft", firstUser.getId(), microsoftSession.getVerifierRef()));
    }

    @Test
    void pairingCodeAndHandoffTicketAreSingleUse() {
        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        NativePrepareResponse prepared = prepare(pairing);
        NativeConnectException pairingReplay = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.prepare(
                new NativePrepareRequest(
                    pairing.pairingCode(),
                    "http://127.0.0.1:49153/callback")));
        assertEquals("pairing_already_claimed", pairingReplay.getCode());

        stubTokens("code", "issuer", "subject", "replay@example.test");
        String state = queryParameter(prepared.authorizeUrl(), "state");
        complete(prepared, "code", state);
        NativeConnectException ticketReplay = assertThrows(
            NativeConnectException.class,
            () -> complete(prepared, "code", state));
        assertEquals("handoff_already_used", ticketReplay.getCode());
    }

    @Test
    void expiredPairingAndHandoffAreRejected() {
        NativePairingResponse expiredPairing = nativeConnectService.createPairing(PROVIDER);
        NativeConnectSession pairingSession = latest(firstUser);
        expire(pairingSession);
        NativeConnectException pairingError = assertThrows(
            NativeConnectException.class,
            () -> prepare(expiredPairing));
        assertEquals("pairing_expired", pairingError.getCode());

        authenticate(firstUser);
        NativePrepareResponse expiredHandoff = prepare(
            nativeConnectService.createPairing(PROVIDER));
        expire(latest(firstUser));
        NativeConnectException handoffError = assertThrows(
            NativeConnectException.class,
            () -> complete(
                expiredHandoff,
                "code",
                queryParameter(expiredHandoff.authorizeUrl(), "state")));
        assertEquals("handoff_expired", handoffError.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:49152/callback",
        "http://[::1]:49152/callback"
    })
    void exactLoopbackRedirectUrisAreAccepted(String redirectUri) {
        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        clearAuthentication();

        NativePrepareResponse prepared = nativeConnectService.prepare(
            new NativePrepareRequest(pairing.pairingCode(), redirectUri));

        assertEquals(
            redirectUri,
            queryParameter(prepared.authorizeUrl(), "redirect_uri"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:49152/callback",
        "https://127.0.0.1:49152/callback",
        "http://127.0.0.1:80/callback",
        "http://evil.example.com/callback",
        "http://127.0.0.1:49152/callback?x=1",
        "http://127.0.0.1:49152/other",
        "http://user@127.0.0.1:49152/callback"
    })
    void nonExactLoopbackRedirectUrisAreRejected(String redirectUri) {
        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        clearAuthentication();

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.prepare(
                new NativePrepareRequest(pairing.pairingCode(), redirectUri)));

        assertEquals("invalid_redirect_uri", error.getCode());
    }

    @Test
    void stateMismatchFailsClaimWithoutCallingProvider() {
        NativePrepareResponse prepared = prepare(
            nativeConnectService.createPairing(PROVIDER));

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> complete(prepared, "code", "wrong-state"));

        assertEquals("state_mismatch", error.getCode());
        authenticate(firstUser);
        NativePairingStatusResponse status =
            nativeConnectService.pairingStatus(PROVIDER);
        assertEquals("failed", status.status());
        assertEquals("state_mismatch", status.errorCode());
        assertNull(connectionMapper.getByUserAndProvider(firstUser.getId(), PROVIDER));
    }

    @Test
    void pkceVerifierIsExchangedButNeverReturned() {
        AtomicReference<Map<String, String>> exchangedForm = new AtomicReference<>();
        when(tokenClient.exchange(anyString(), anyMap())).thenAnswer(invocation -> {
            Map<String, String> form = invocation.getArgument(1);
            exchangedForm.set(Map.copyOf(form));
            return tokenResponse(
                "issuer", "subject", "pkce@example.test", form.get("code"));
        });
        NativePairingResponse pairing = nativeConnectService.createPairing(PROVIDER);
        NativePrepareResponse prepared = prepare(pairing);
        String state = queryParameter(prepared.authorizeUrl(), "state");

        complete(prepared, "pkce-code", state);

        String verifier = exchangedForm.get().get("code_verifier");
        assertNotNull(verifier);
        assertBearerSecret(verifier);
        assertEquals(
            NativeConnectPkce.challenge(verifier),
            queryParameter(prepared.authorizeUrl(), "code_challenge"));
        assertEquals("S256", queryParameter(
            prepared.authorizeUrl(), "code_challenge_method"));
        assertEquals("authorization_code", exchangedForm.get().get("grant_type"));
        assertEquals("pkce-code", exchangedForm.get().get("code"));
        assertEquals(MANAGED_CLIENT_ID, exchangedForm.get().get("client_id"));
        assertEquals(
            "http://127.0.0.1:49152/callback",
            exchangedForm.get().get("redirect_uri"));
        assertFalse(exchangedForm.get().containsKey("client_secret"));
        assertFalse(prepared.toString().contains(verifier));
        assertFalse(pairing.toString().contains(verifier));
        authenticate(firstUser);
        assertFalse(nativeConnectService.pairingStatus(PROVIDER).toString().contains(verifier));
    }

    @Test
    void idTokenAudienceUsesManagedClientId() {
        when(tokenClient.exchange(anyString(), anyMap())).thenReturn(
            tokenResponse(
                "operator-client-id",
                "issuer",
                "subject",
                "audience@example.test",
                "code"));
        NativePrepareResponse prepared = prepare(
            nativeConnectService.createPairing(PROVIDER));

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> complete(
                prepared,
                "code",
                queryParameter(prepared.authorizeUrl(), "state")));

        assertEquals("identity_audience_mismatch", error.getCode());
        assertNull(connectionMapper.getByUserAndProvider(firstUser.getId(), PROVIDER));
    }

    @Test
    void managedAndCustomModesGateOppositeConnectPaths() {
        BadRequestException legacyError = assertThrows(
            BadRequestException.class,
            () -> providerConnectionService.beginAuthorization(PROVIDER));
        assertEquals(
            "This instance uses the Connex-managed connection flow for google",
            legacyError.getMessage());

        properties.getGoogle().setMode(ConnectedAccountMode.CUSTOM);
        NativeConnectException nativeError = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.createPairing(PROVIDER));
        assertEquals("custom_connection_flow", nativeError.getCode());
    }

    @Test
    void blankManagedIdentityFailsClosedWithDistinctCode() {
        properties.getManaged().getGoogle().setClientId("");

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.createPairing(PROVIDER));

        assertEquals("managed_identity_unavailable", error.getCode());
    }

    @Test
    void plaintextNonLoopbackInstanceBaseUrlFailsClosed() {
        mailProperties.setAppBaseUrl("http://instance.example.test");

        NativeConnectException error = assertThrows(
            NativeConnectException.class,
            () -> nativeConnectService.createPairing(PROVIDER));

        assertEquals("instance_base_url_unavailable", error.getCode());
        assertEquals(
            "none",
            nativeConnectService.pairingStatus(PROVIDER).status());
    }

    private NativePrepareResponse prepare(NativePairingResponse pairing) {
        clearAuthentication();
        return nativeConnectService.prepare(new NativePrepareRequest(
            pairing.pairingCode(), "http://127.0.0.1:49152/callback"));
    }

    private NativeCompleteResponse complete(
            NativePrepareResponse prepared,
            String code,
            String state) {
        clearAuthentication();
        return nativeConnectService.complete(new NativeCompleteRequest(
            prepared.handoffTicket(), code, state));
    }

    private void stubTokens(String code, String issuer, String subject, String email) {
        when(tokenClient.exchange(anyString(), anyMap())).thenReturn(
            tokenResponse(issuer, subject, email, code));
    }

    private static ProviderTokenResponse tokenResponse(
            String issuer,
            String subject,
            String email,
            String code) {
        return tokenResponse(
            MANAGED_CLIENT_ID, issuer, subject, email, code);
    }

    private static ProviderTokenResponse tokenResponse(
            String audience,
            String issuer,
            String subject,
            String email,
            String code) {
        return new ProviderTokenResponse(
            "access-" + code,
            "refresh-" + code,
            3600L,
            "openid email scope-a",
            fakeIdToken(audience, issuer, subject, email));
    }

    private static String fakeIdToken(
            String audience,
            String issuer,
            String subject,
            String email) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"aud\":\"" + audience
                + "\",\"iss\":\"" + issuer
                + "\",\"sub\":\"" + subject
                + "\",\"email\":\"" + email + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private ProviderConnection connection(User user) {
        ProviderConnection connection =
            connectionMapper.getByUserAndProvider(user.getId(), PROVIDER);
        assertNotNull(connection);
        return connection;
    }

    private NativeConnectSession latest(User user) {
        return latest(user, PROVIDER);
    }

    private NativeConnectSession latest(User user, String provider) {
        NativeConnectSession session =
            nativeSessionMapper.getLatestByUserAndProvider(user.getId(), provider);
        assertNotNull(session);
        return session;
    }

    private void expire(NativeConnectSession session) {
        jdbcTemplate.update(
            "UPDATE provider_native_connect_session "
                + "SET expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE id = ?",
            session.getId());
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("native_" + suffix);
        user.setDisplayName("Native " + suffix);
        user.setEmail("native_" + suffix + "@example.test");
        user.setPasswordHash("hash_" + suffix);
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return user;
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        long now = System.currentTimeMillis();
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        request.getSession().setAttribute(
            SessionSecurityService.AUTHENTICATED_USER_ATTR, user.getId());
        request.getSession().setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        request.getSession().setAttribute(
            SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, user.getId());
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(request));
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        tenantContext.set(
            workspace.getId(),
            orgId == null ? workspace.getId() : orgId,
            user.getId(),
            "member",
            null);
    }

    private void clearAuthentication() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        tenantContext.clear();
    }

    private static String queryParameter(String url, String name) {
        String query = URI.create(url).getRawQuery();
        assertNotNull(query);
        for (String parameter : query.split("&")) {
            String[] parts = parameter.split("=", 2);
            if (parts[0].equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }

    private static void assertBearerSecret(String value) {
        assertFalse(value.contains("="));
        assertEquals(32, Base64.getUrlDecoder().decode(value).length);
    }
}
