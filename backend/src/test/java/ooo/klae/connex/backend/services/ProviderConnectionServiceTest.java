package ooo.klae.connex.backend.services;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestAttributes;
import org.mybatis.spring.SqlSessionTemplate;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionService;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenClient;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.connectedaccounts.UserProviderSecretCipher;
import ooo.klae.connex.backend.controllers.ProviderConnectionController;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProviderConnectionServiceTest extends AbstractServiceTest {
    private static final String PENDING_STATE = "connex.connectedAccounts.pendingState";

    @Autowired ProviderConnectionService connectionService;
    @Autowired ConnectedAccountProperties properties;
    @Autowired UserProviderSecretCipher secretCipher;
    @Autowired ProviderConnectionMapper providerConnectionMapper;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired SessionRepository<? extends Session> sessionRepository;
    @Autowired CookieSerializer cookieSerializer;
    @Autowired @Qualifier("springSessionRepositoryFilter") Filter sessionFilter;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SqlSessionTemplate sqlSessionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean SpringSessionMapper springSessionMapper;
    @MockitoBean ProviderTokenClient tokenClient;
    @MockitoBean AuditService auditService;

    private final List<Integer> fixtureUsers = new ArrayList<>();
    private Organization organization;
    private String sessionId;
    private MockMvc mockMvc;

    @Override
    @BeforeEach
    protected void setUpWorkspaceAndAuthentication() {
        clearRequestContext();
        String suffix = unique();
        organization = new Organization();
        organization.setName("Provider callback " + suffix);
        organization.setSlug("provider-callback-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Provider callback " + suffix);
        workspace.setSlug("provider-callback-" + suffix);
        workspaceMapper.insert(workspace);
        currentUser = newUser();
        authenticateAs(currentUser, workspace.getId());
        enableGoogle();
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        fixtureUsers.add(user.getId());
        return user;
    }

    private void enableGoogle() {
        properties.getGoogle().setEnabled(true);
        properties.getGoogle().setClientId("client-id");
        properties.getGoogle().setClientSecret("client-secret");
        MockHttpSession session = persistSession(sessionRepository, currentSession());
        sessionId = session.getId();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProviderConnectionController(connectionService))
            .addFilters(sessionFilter)
            .build();
    }

    @AfterEach
    void resetProviders() {
        properties.getGoogle().setEnabled(false);
        properties.getGoogle().setClientId(null);
        properties.getGoogle().setClientSecret(null);
        properties.getMicrosoft().setEnabled(false);
        properties.getMicrosoft().setClientId(null);
        properties.getMicrosoft().setClientSecret(null);
        if (sessionId != null) {
            sessionRepository.deleteById(sessionId);
        }
        clearRequestContext();
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        for (int userId : fixtureUsers) {
            userMapper.delete(userId);
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    private static String fakeIdToken(String email, String accountId) {
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("{\"aud\":\"client-id\",\"iss\":\"https://accounts.example.test\","
                + "\"sub\":\"" + accountId + "\",\"email\":\"" + email + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private String beginAndExtractState() {
        String url = connectionService.beginAuthorization("google");
        persistPendingState(sessionRepository, currentSession());
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("access_type=offline"));
        for (String param : url.substring(url.indexOf('?') + 1).split("&")) {
            if (param.startsWith("state=")) {
                return URLDecoder.decode(param.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("authorize URL carries no state: " + url);
    }

    private static HttpSession currentSession() {
        if (RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpSession session = attributes.getRequest().getSession(false);
            assertNotNull(session);
            return session;
        }
        throw new AssertionError("Missing servlet request");
    }

    private static <S extends Session> MockHttpSession persistSession(
            SessionRepository<S> repository, HttpSession original) {
        S stored = repository.createSession();
        MockHttpSession session = new MockHttpSession(null, stored.getId());
        var names = original.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Object value = original.getAttribute(name);
            stored.setAttribute(name, value);
            session.setAttribute(name, value);
        }
        repository.save(stored);
        return session;
    }

    private static <S extends Session> void persistPendingState(
            SessionRepository<S> repository, HttpSession session) {
        S stored = repository.findById(session.getId());
        assertNotNull(stored);
        stored.setAttribute(PENDING_STATE, session.getAttribute(PENDING_STATE));
        repository.save(stored);
    }

    /**
     * Uses the active serializer because this mock web context takes cookie settings from its
     * servlet context, not the embedded server configuration. Verifies that the generated cookie
     * resolves the same JDBC session that holds the pending state.
     */
    private Cookie sessionCookie() {
        MockHttpServletResponse written = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(
            new MockHttpServletRequest(), written, sessionId));
        String header = written.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(header);
        Cookie cookie = MockCookie.parse(header);
        MockHttpServletRequest carrier = new MockHttpServletRequest();
        carrier.setCookies(cookie);
        assertEquals(List.of(sessionId), cookieSerializer.readCookieValues(carrier));
        return cookie;
    }

    private String httpCallback(String provider, String state, boolean denied) throws Exception {
        var request = get("/api/account/connections/callback/" + provider)
            .cookie(sessionCookie()).param("state", state);
        if (denied) {
            request.param("error", "access_denied");
        } else {
            request.param("code", "auth-code");
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertEquals(302, response.getStatus());
        String redirect = response.getRedirectedUrl();
        assertNotNull(redirect);
        return redirect;
    }

    private void stubExchange(String refreshToken, String email) {
        stubExchange(refreshToken, email, "provider-account");
    }

    private void stubExchange(String refreshToken, String email, String accountId) {
        when(tokenClient.exchange(anyString(), any())).thenReturn(new ProviderTokenResponse(
            "access-token", refreshToken, 3600L, "openid email scope-a",
            fakeIdToken(email, accountId)));
    }

    private String storedReference() {
        ProviderConnection connection =
            providerConnectionMapper.getByUserAndProvider(currentUser.getId(), "google");
        assertNotNull(connection);
        return connection.getCredentialRef();
    }

    @Test
    void unconfiguredProviderFailsClosed() {
        assertThrows(BadRequestException.class, () -> connectionService.beginAuthorization("microsoft"));
        assertThrows(ResourceNotFoundException.class, () -> connectionService.beginAuthorization("slack"));
    }

    @Test
    void connectStoresEncryptedBundleAndConnectionRow() {
        stubExchange("refresh-token", "sales@example.com");
        String state = beginAndExtractState();

        String redirect = connectionService.completeCallback("google", "auth-code", state, null);
        assertTrue(redirect.endsWith("/account/connections?connected=google"));

        List<ProviderConnectionDto> connections = connectionService.getForCurrentUser();
        assertEquals(1, connections.size());
        ProviderConnectionDto connection = connections.getFirst();
        assertEquals("google", connection.provider());
        assertEquals("connected", connection.status());
        assertEquals("sales@example.com", connection.providerAccountEmail());
        assertTrue(connection.hasCredential());
        assertTrue(secretCipher.decryptTokenBundle("google", currentUser.getId(), storedReference())
            .contains("refresh-token"));
    }

    @Test
    void callbackRejectsMissingWrongAndReplayedState() {
        stubExchange("refresh-token", "sales@example.com");

        assertTrue(connectionService.completeCallback("google", "code", null, null).contains("error=state"));
        assertTrue(connectionService.completeCallback("google", "code", "forged", null).contains("error=state"));

        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("connected=google"));
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("error=state"));
    }

    @Test
    void forgedGetCallbackPreservesThePendingFlowAndTheRealCallbackSucceedsOnce() throws Exception {
        String state = beginAndExtractState();

        assertTrue(httpCallback("google", "forged", true).contains("error=state"));
        verifyNoInteractions(tokenClient);

        stubExchange("refresh-token", "sales@example.com");
        assertTrue(httpCallback("google", state, false).contains("connected=google"));
        assertEquals(1, connectionService.getForCurrentUser().size());
        assertTrue(httpCallback("google", state, false).contains("error=state"));
        verify(tokenClient).exchange(anyString(), any());
    }

    @Test
    void callbackRequiresMatchingProviderState() {
        properties.getMicrosoft().setEnabled(true);
        properties.getMicrosoft().setClientId("ms-id");
        properties.getMicrosoft().setClientSecret("ms-secret");
        String state = beginAndExtractState();

        assertTrue(connectionService.completeCallback("microsoft", "code", state, null).contains("error=state"));
        stubExchange("refresh-token", "sales@example.com");
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("connected=google"));
        assertTrue(connectionService.completeCallback("google", "code", state, null).contains("error=state"));
    }

    @Test
    void expiredCallbackDoesNotMutatePendingState() throws Exception {
        String state = beginAndExtractState();
        String stored = assertInstanceOf(String.class, currentSession().getAttribute(PENDING_STATE));
        String[] parts = stored.split("\\|", 4);
        String expired = parts[0] + "|" + parts[1] + "|0|" + parts[3];
        currentSession().setAttribute(PENDING_STATE, expired);
        persistPendingState(sessionRepository, currentSession());

        assertTrue(httpCallback("google", state, false).contains("error=state"));

        Session persisted = sessionRepository.findById(sessionId);
        assertNotNull(persisted);
        assertEquals(expired, persisted.getAttribute(PENDING_STATE));
        verifyNoInteractions(tokenClient);
    }

    @Test
    void concurrentCallbacksContendOnTheStoredStateAndOnlyOneConsumesIt() throws Exception {
        String state = beginAndExtractState();
        CountDownLatch firstClaimLocked = new CountDownLatch(1);
        CountDownLatch secondClaimAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirstClaim = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SpringSessionMapper realMapper = sqlSessionTemplate.getMapper(SpringSessionMapper.class);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0, String.class);
            byte[] expected = invocation.getArgument(1, byte[].class);
            byte[] consumed = invocation.getArgument(2, byte[].class);
            if (calls.incrementAndGet() == 1) {
                return new TransactionTemplate(transactionManager).execute(status -> {
                    int result = realMapper.consumeProviderConnectionState(id, expected, consumed);
                    assertEquals(1, result);
                    firstClaimLocked.countDown();
                    await(releaseFirstClaim);
                    return result;
                });
            }
            secondClaimAttempted.countDown();
            return realMapper.consumeProviderConnectionState(id, expected, consumed);
        }).when(springSessionMapper).consumeProviderConnectionState(anyString(), any(), any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> callbackOnWorker(state));
            assertTrue(firstClaimLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> callbackOnWorker(state));
            assertTrue(secondClaimAttempted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));

            releaseFirstClaim.countDown();

            assertTrue(first.get(15, TimeUnit.SECONDS).contains("error=denied"));
            assertTrue(second.get(15, TimeUnit.SECONDS).contains("error=state"));
            verifyNoInteractions(tokenClient);
        } finally {
            releaseFirstClaim.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void callbackResponseCannotOverwriteANewerAuthorization() throws Exception {
        String state = beginAndExtractState();
        RequestAttributes originalRequest = RequestContextHolder.currentRequestAttributes();
        AtomicReference<String> replacementState = new AtomicReference<>();
        when(tokenClient.exchange(anyString(), any())).thenAnswer(invocation -> {
            RequestAttributes callbackRequest = RequestContextHolder.currentRequestAttributes();
            try {
                RequestContextHolder.setRequestAttributes(originalRequest);
                replacementState.set(beginAndExtractState());
            } finally {
                RequestContextHolder.setRequestAttributes(callbackRequest);
            }
            return new ProviderTokenResponse("access-token", "refresh-token", 3600L,
                "openid email scope-a", fakeIdToken("sales@example.com", "provider-account"));
        });

        assertTrue(httpCallback("google", state, false).contains("connected=google"));
        String replacement = replacementState.get();
        assertNotNull(replacement);
        assertTrue(httpCallback("google", state, true).contains("error=state"));
        assertTrue(httpCallback("google", replacement, true).contains("error=denied"));
        assertTrue(httpCallback("google", replacement, true).contains("error=state"));
    }

    private String callbackOnWorker(String state) throws Exception {
        authenticateAs(currentUser, workspace.getId());
        try {
            return httpCallback("google", state, true);
        } finally {
            clearAuthentication();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Provider state claim was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider state claim was interrupted", exception);
        }
    }

    @Test
    void deniedConsentRedirectsWithoutStoringAnything() {
        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", null, state, "access_denied")
            .contains("error=denied"));
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void withheldRefreshTokenIsRejected() {
        stubExchange(null, "sales@example.com");
        String state = beginAndExtractState();
        assertTrue(connectionService.completeCallback("google", "code", state, null)
            .contains("error=no_offline_access"));
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void lifecyclePauseResumeDisconnect() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        assertEquals("paused", connectionService.pause("google").status());
        assertThrows(BadRequestException.class, () -> connectionService.pause("google"));
        assertEquals("connected", connectionService.resume("google").status());

        connectionService.disconnect("google");
        assertTrue(connectionService.getForCurrentUser().isEmpty());
        ProviderConnection tombstone = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        assertEquals("disconnected", tombstone.getStatus());
        assertEquals(4, tombstone.getCredentialGeneration());
        assertNull(tombstone.getCredentialRef());
    }

    @Test
    void reconnectReplacesBundleForSameUserAndProvider() {
        stubExchange("refresh-token-1", "old@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        stubExchange("refresh-token-2", "new@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        List<ProviderConnectionDto> connections = connectionService.getForCurrentUser();
        assertEquals(1, connections.size());
        assertEquals("new@example.com", connections.getFirst().providerAccountEmail());

        String bundle = secretCipher.decryptTokenBundle("google", currentUser.getId(), storedReference());
        assertTrue(bundle.contains("refresh-token-2"));
        assertFalse(bundle.contains("refresh-token-1"));
    }

    @Test
    void reconnectFromDisconnectedTombstoneAdvancesGenerationByOne() {
        stubExchange("refresh-token-1", "old@example.com");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);
        connectionService.disconnect("google");
        ProviderConnection tombstone = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        long tombstoneGeneration = tombstone.getCredentialGeneration();

        stubExchange("refresh-token-2", "new@example.com");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);

        ProviderConnection reconnected = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        assertEquals(tombstoneGeneration + 1, reconnected.getCredentialGeneration());
        assertEquals("connected", reconnected.getStatus());
        assertNotNull(reconnected.getCredentialRef());
    }

    @Test
    void callbackStartedBeforeDisconnectCannotRestoreTheCredential() {
        stubExchange("refresh-token-1", "old@example.com");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);
        String staleState = beginAndExtractState();

        connectionService.disconnect("google");
        ProviderConnection tombstone = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        long disconnectedGeneration = tombstone.getCredentialGeneration();

        stubExchange("refresh-token-2", "old@example.com");
        assertTrue(connectionService.completeCallback(
            "google", "code", staleState, null).contains("error=exchange"));

        ProviderConnection retained = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        assertEquals("disconnected", retained.getStatus());
        assertEquals(disconnectedGeneration, retained.getCredentialGeneration());
        assertNull(retained.getCredentialRef());
    }

    @Test
    void disconnectDuringCodeExchangeRevokesTheSupersededGoogleGrant() {
        stubExchange("refresh-token-1", "old@example.com");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);
        String staleState = beginAndExtractState();
        ProviderTokenResponse replacement = new ProviderTokenResponse(
            "access-token-2",
            "refresh-token-2",
            3600L,
            "openid email scope-a",
            fakeIdToken("old@example.com", "provider-account"));
        when(tokenClient.exchange(anyString(), any())).thenAnswer(invocation -> {
            connectionService.disconnect("google");
            return replacement;
        });

        assertTrue(connectionService.completeCallback(
            "google", "code", staleState, null).contains("error=exchange"));

        verify(tokenClient).revoke(
            "https://oauth2.googleapis.com/revoke", "refresh-token-2");
        ProviderConnection tombstone = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        assertEquals("disconnected", tombstone.getStatus());
        assertNull(tombstone.getCredentialRef());
    }

    @Test
    void disconnectedTombstoneRejectsADifferentProviderAccount() {
        stubExchange("refresh-token-1", "old@example.com", "account-old");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);
        connectionService.disconnect("google");

        stubExchange("refresh-token-2", "new@example.com", "account-new");
        assertTrue(connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null)
            .contains("error=retained_data_reset_required"));
        verify(tokenClient).revoke(
            "https://oauth2.googleapis.com/revoke", "refresh-token-2");

        ProviderConnection tombstone = providerConnectionMapper
            .getByUserAndProvider(currentUser.getId(), "google");
        assertEquals("disconnected", tombstone.getStatus());
        assertEquals(
            "google:https://accounts.example.test:account-old",
            tombstone.getProviderAccountId());
        assertNull(tombstone.getCredentialRef());
    }

    @Test
    void explicitAllWorkspaceResetDeletesTheRetainedIdentityTombstone() {
        stubExchange("refresh-token-1", "old@example.com", "account-old");
        connectionService.completeCallback(
            "google", "code", beginAndExtractState(), null);
        connectionService.disconnect("google");

        connectionService.eraseAllCapturedDataAndReset("google");

        assertNull(providerConnectionMapper.getByUserAndProvider(
            currentUser.getId(), "google"));
    }

    @Test
    void connectionsAreSelfScoped() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);

        User other = newUser();
        authenticateAs(other, workspace.getId());
        assertTrue(connectionService.getForCurrentUser().isEmpty());
        assertThrows(ResourceNotFoundException.class, () -> connectionService.pause("google"));
        assertThrows(ResourceNotFoundException.class, () -> connectionService.disconnect("google"));

        authenticateAs(currentUser, workspace.getId());
        assertEquals(1, connectionService.getForCurrentUser().size());
    }

    @Test
    void disconnectSurvivesADanglingSecretReference() {
        ProviderConnection dangling = new ProviderConnection();
        dangling.setUserId(currentUser.getId());
        dangling.setProvider("google");
        dangling.setStatus("connected");
        dangling.setProviderAccountId("provider-account");
        dangling.setProviderAccountEmail("sales@example.com");
        dangling.setGrantedScopes("openid email");
        dangling.setCredentialRef("secret:v1:999999999");
        dangling.setCredentialGeneration(1);
        providerConnectionMapper.insert(dangling);

        connectionService.disconnect("google");
        assertTrue(connectionService.getForCurrentUser().isEmpty());
    }

    @Test
    void tokenBundleIsScopedToItsOwner() {
        stubExchange("refresh-token", "sales@example.com");
        connectionService.completeCallback("google", "code", beginAndExtractState(), null);
        String reference = storedReference();

        assertNotNull(secretCipher.decryptTokenBundle("google", currentUser.getId(), reference));
        User other = newUser();
        assertThrows(RuntimeException.class,
            () -> secretCipher.decryptTokenBundle("google", other.getId(), reference));
    }
}
