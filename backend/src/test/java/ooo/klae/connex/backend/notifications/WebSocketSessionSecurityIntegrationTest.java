package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.PasswordResetToken;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.dto.CsrfBootstrapDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PasswordResetEmailService;
import ooo.klae.connex.backend.services.PasswordResetService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;
import tools.jackson.databind.ObjectMapper;

/** Exercises immutable destinations and receive-only revocation over the real broker and JDBC store. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"server.address=127.0.0.1", "server.servlet.session.cookie.secure=false"})
class WebSocketSessionSecurityIntegrationTest {

    private static final String PASSWORD = "WebSocket-Fixture-Pw1!";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String ORIGIN = "http://localhost:3000";

    @LocalServerPort private int port;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordResetTokenMapper passwordResetTokenMapper;
    @Autowired private PasswordResetService passwordResetService;
    @MockitoBean private PasswordResetEmailService passwordResetEmailService;
    @Autowired private SimpNotificationRealtimePublisher notificationPublisher;
    @Autowired private SimpAiChatRealtimePublisher aiChatPublisher;
    @Autowired private RealtimeRoutingIdentityResolver routingIdentities;
    @Autowired private SessionRegistry securitySessionRegistry;
    @Autowired private FindByIndexNameSessionRepository<? extends Session> indexedSessions;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @Autowired private SessionSecurityProperties sessionSecurityProperties;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired @Qualifier("brokerChannel") private AbstractSubscribableChannel brokerChannel;

    private final List<Browser> browsers = new ArrayList<>();
    private final List<SocketClient> sockets = new ArrayList<>();
    private final SubscriptionBarrier subscriptions = new SubscriptionBarrier();

    @BeforeEach
    void observeBrokerSubscriptions() {
        brokerChannel.addInterceptor(subscriptions);
    }

    @AfterEach
    void closeClients() {
        for (SocketClient socket : sockets) {
            socket.abort();
        }
        for (Browser browser : browsers) {
            sessionRepository.deleteById(browser.sessionId());
            browser.client().close();
        }
        brokerChannel.removeInterceptor(subscriptions);
    }

    /**
     * Spring echoes the routing principal back to the browser in the {@code CONNECTED} frame's
     * {@code user-name} header, so the header must carry the opaque token rather than the account.
     */
    @Test
    void theHandshakePrincipalIsAnOpaqueTokenThatDoesNotDiscloseTheAccount() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);
        int accountId = browser.account().user().getId();

        String connected = socket.connected.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(connected.contains(
            "user-name:" + routingIdentities.destinationFor(accountId) + "\n"));
        assertFalse(connected.contains("uid:"));
        assertFalse(connected.contains(browser.account().user().getUsername()));
    }

    /**
     * The routing identity is derived from the authenticated handshake alone. A client-supplied
     * identity header on {@code CONNECT} must neither become the principal nor subscribe the socket
     * to another account's queues.
     */
    @Test
    void aForgedConnectIdentityHeaderCannotChangeTheResolvedPrincipal() throws Exception {
        Browser victim = login(newAccount());
        String forged = routingIdentities.destinationFor(victim.account().user().getId());
        Browser attacker = login(newAccount());
        SocketClient socket = connect(attacker,
            "user-name:" + forged + "\nlogin:" + victim.account().user().getUsername() + "\n");

        String connected = socket.connected.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(connected.contains("user-name:"
            + routingIdentities.destinationFor(attacker.account().user().getId()) + "\n"));
        assertFalse(connected.contains(forged));

        publish(victim.account(), "victim-private-frame", 7);
        publish(attacker.account(), "attacker-own-frame", 8);

        assertDelivery(socket, "attacker-own-frame", 8);
        assertTrue(socket.messages.isEmpty(), "forged principal received another account's frames");
    }

    /**
     * Pins the persisted expiry marker {@code SpringSessionBackedSessionInformation} writes, which
     * delivery validation reads off the session row rather than loading it a second time.
     */
    @Test
    void registryExpiryClosesAReceiveOnlySocketAtDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);

        expireThroughRegistry(browser);
        publish(browser.account(), "after-registry-expiry", 42);

        assertClosedWithoutMessages(socket);
    }

    /** After-commit publishers can still carry the completed tenant transaction's thread state. */
    @Test
    void tenantTransactionStateDoesNotLeakIntoOutboundSessionValidation() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);
        String recipient = routingIdentities.destinationFor(browser.account().user().getId());

        tenantWorkScope.withCatalog("dedicated-fixture", () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                messagingTemplate.convertAndSendToUser(recipient, "/queue/ai-chat",
                    AiChatStepFrameDto.delta(browser.account().workspace().getId(), 1, 1, 2,
                        "after-tenant-commit"));
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            return null;
        });

        String delivered = socket.messages.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(delivered);
        assertTrue(delivered.contains("after-tenant-commit"));
        assertFalse(socket.closed.isDone());
    }

    @Test
    void recycledUsernameCannotReceiveAnotherOrganizationsNotificationOrAssistantDelta() throws Exception {
        Browser attacker = login(newAccount());
        String recycled = attacker.account().user().getUsername();
        SocketClient oldSocket = connect(attacker);
        rename(attacker, recycled + "-renamed");

        Browser victim = login(newAccount());
        assertFalse(attacker.account().workspace().getOrgId() == victim.account().workspace().getOrgId());
        rename(victim, recycled);
        SocketClient victimSocket = connect(victim);

        publish(victim.account(), "victim-private-answer", 42);

        assertDelivery(victimSocket, "victim-private-answer", 42);
        assertClosedWithoutMessages(oldSocket);
    }

    @Test
    void passwordResetClosesAReceiveOnlySocketBeforeFurtherDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);
        passwordResetService.requestReset(browser.account().user().getEmail(), "198.51.100.61");
        ArgumentCaptor<String> tokenCapture = ArgumentCaptor.forClass(String.class);
        verify(passwordResetEmailService).sendResetEmail(any(User.class), tokenCapture.capture());
        String rawToken = tokenCapture.getValue();
        PasswordResetToken token = passwordResetTokenMapper.findRedeemableByHash(
            OneTimeTokenDigest.sha256(rawToken));
        assertNotNull(token);
        assertEquals(browser.account().user().getId(), token.getUserId());
        assertNotNull(token.getCredentialGeneration());
        assertEquals(userMapper.currentSessionEpoch(token.getUserId()), token.getCredentialGeneration());

        passwordResetService.resetPassword(rawToken, "WebSocket-Replacement-Pw2!");
        publish(browser.account(), "after-password-reset", 42);

        assertClosedWithoutMessages(socket);
    }

    @Test
    void logoutClosesAReceiveOnlySocketBeforeFurtherDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);

        HttpResponse<String> response = request(browser, "POST", "/api/auth/logout", "");
        assertEquals(200, response.statusCode());
        publish(browser.account(), "after-logout", 42);

        assertClosedWithoutMessages(socket);
    }

    @Test
    void jdbcIdleExpiryClosesAReceiveOnlySocketAtDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);

        expireIdleSession(sessionRepository, browser.sessionId());
        assertNull(sessionRepository.findById(browser.sessionId()));
        publish(browser.account(), "after-idle-expiry", 42);

        assertClosedWithoutMessages(socket);
    }

    @Test
    void aDeletedBackingSessionClosesAReceiveOnlySocketAtDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);

        sessionRepository.deleteById(browser.sessionId());
        publish(browser.account(), "after-session-removal", 42);

        assertClosedWithoutMessages(socket);
    }

    @Test
    void anEpochBumpWithoutSessionEnumerationClosesAReceiveOnlySocketAtDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);

        userMapper.bumpSessionEpoch(browser.account().user().getId());
        assertNotNull(sessionRepository.findById(browser.sessionId()));
        publish(browser.account(), "after-epoch-bump", 42);

        assertClosedWithoutMessages(socket);
    }

    @Test
    void absoluteSessionLifetimeClosesAReceiveOnlySocketAtDelivery() throws Exception {
        Browser browser = login(newAccount());
        SocketClient socket = connect(browser);
        Duration timeout = sessionSecurityProperties.getAbsoluteTimeout();
        assertNotNull(timeout);
        assertTrue(timeout.isPositive());

        setAuthenticatedAt(sessionRepository, browser.sessionId(),
            clock.millis() - timeout.toMillis() - 1_000);
        assertNotNull(sessionRepository.findById(browser.sessionId()));
        publish(browser.account(), "after-absolute-expiry", 42);

        assertClosedWithoutMessages(socket);
    }

    private Account newAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("WebSocket " + suffix);
        organization.setSlug("ws-org-" + suffix);
        organizationMapper.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("WebSocket " + suffix);
        workspace.setSlug("ws-workspace-" + suffix);
        workspaceMapper.insert(workspace);
        User user = new User();
        user.setUsername("ws-" + suffix);
        user.setDisplayName("WebSocket " + suffix);
        user.setEmail("ws-" + suffix + "@example.com");
        user.setTimezone("UTC");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        userMapper.insert(user);
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return new Account(user, workspace);
    }

    private Browser login(Account account) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(httpUri("/api/auth/login"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                Map.of("username", account.user().getUsername(), "password", PASSWORD))))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
        HttpResponse<String> bootstrap = client.send(HttpRequest.newBuilder(httpUri("/api/auth/csrf"))
            .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, bootstrap.statusCode(), bootstrap.body());
        CsrfBootstrapDto csrf = objectMapper.readValue(bootstrap.body(), CsrfBootstrapDto.class);
        assertNotNull(csrf);
        assertNotNull(csrf.headerName());
        assertNotNull(csrf.token());
        Map<String, ? extends Session> stored = indexedSessions.findByPrincipalName(
            new AccountSessionIndex(account.user().getId()).getName());
        assertEquals(1, stored.size());
        Browser browser = new Browser(account, client, csrf, stored.keySet().iterator().next());
        browsers.add(browser);
        return browser;
    }

    private SocketClient connect(Browser browser) throws Exception {
        return connect(browser, "");
    }

    private SocketClient connect(Browser browser, String extraConnectHeaders) throws Exception {
        SocketClient socket = new SocketClient();
        sockets.add(socket);
        socket.webSocket = browser.client().newWebSocketBuilder()
            .connectTimeout(TIMEOUT)
            .header("Origin", ORIGIN)
            .subprotocols("v12.stomp")
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws"), socket)
            .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        socket.send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n"
            + extraConnectHeaders
            + browser.csrf().headerName() + ":" + browser.csrf().token() + "\n\n\0");
        socket.connected.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        subscribe(socket, "notifications");
        subscribe(socket, "ai-chat");
        publish(browser.account(), "subscription-probe", 1);
        assertDelivery(socket, "subscription-probe", 1);
        return socket;
    }

    private void subscribe(SocketClient socket, String queue) throws Exception {
        String receipt = UUID.randomUUID().toString();
        CompletableFuture<Void> subscribed = subscriptions.register(receipt);
        socket.send("SUBSCRIBE\nid:" + queue + "\ndestination:/user/queue/" + queue
            + "\nreceipt:" + receipt + "\n\n\0");
        subscribed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private void rename(Browser browser, String username) throws Exception {
        UserDto dto = UserDto.from(browser.account().user());
        assertNotNull(dto);
        dto.setUsername(username);
        HttpResponse<String> response = request(browser, "PUT",
            "/api/users/" + browser.account().user().getId(), objectMapper.writeValueAsString(dto));
        assertEquals(200, response.statusCode(), response.body());
        browser.account().user().setUsername(username);
    }

    private HttpResponse<String> request(Browser browser, String method, String path, String body)
            throws Exception {
        return browser.client().send(HttpRequest.newBuilder(httpUri(path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-Workspace-Id", Integer.toString(browser.account().workspace().getId()))
            .header(browser.csrf().headerName(), browser.csrf().token())
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI httpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void publish(Account account, String text, long stateVersion) {
        NotificationDto notification = new NotificationDto();
        notification.setId(1);
        notification.setWorkspaceId(account.workspace().getId());
        notification.setTitle("Private notification " + text);
        notification.setBody("Private body " + text);
        notificationPublisher.send(account.user().getId(),
            RealtimeNotificationPayload.created(notification, text, stateVersion));
        aiChatPublisher.sendUser(account.user().getId(),
            AiChatStepFrameDto.delta(account.workspace().getId(), 1, 1, 1, text));
    }

    private static void assertDelivery(SocketClient socket, String text, long stateVersion)
            throws Exception {
        String first = socket.messages.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String second = socket.messages.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(first, "notification/assistant probe was not delivered");
        assertNotNull(second, "notification/assistant probe was not delivered");
        List<String> frames = List.of(first, second);
        assertTrue(frames.stream().anyMatch(frame -> frame.contains("subscription:notifications\n")
            && frame.contains("\"stateVersion\":" + stateVersion)
            && frame.contains("Private body " + text)));
        assertTrue(frames.stream().anyMatch(frame -> frame.contains("subscription:ai-chat\n")
            && frame.contains(text)));
    }

    private static void assertClosedWithoutMessages(SocketClient socket) throws Exception {
        assertEquals(1008, socket.closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertTrue(socket.messages.isEmpty(), "revoked socket received a server push");
    }

    private void expireThroughRegistry(Browser browser) {
        List<SessionInformation> sessions = securitySessionRegistry.getAllSessions(
            new AccountSessionIndex(browser.account().user().getId()), false);
        assertEquals(1, sessions.size());
        assertEquals(browser.sessionId(), sessions.getFirst().getSessionId());
        sessions.getFirst().expireNow();
    }

    private static <S extends Session> void expireIdleSession(
            SessionRepository<S> repository, String id) {
        S session = repository.findById(id);
        assertNotNull(session);
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now().minusSeconds(60));
        repository.save(session);
    }

    private static <S extends Session> void setAuthenticatedAt(
            SessionRepository<S> repository, String id, long authenticatedAt) {
        S session = repository.findById(id);
        assertNotNull(session);
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, authenticatedAt);
        repository.save(session);
    }

    private record Account(User user, Workspace workspace) {
    }

    private record Browser(Account account, HttpClient client, CsrfBootstrapDto csrf, String sessionId) {
    }

    /** Waits for the actual broker subscription write, after user-destination translation. */
    private static final class SubscriptionBarrier implements ExecutorChannelInterceptor {
        private final Map<String, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();

        CompletableFuture<Void> register(String receipt) {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            waiting.put(receipt, ready);
            return ready;
        }

        @Override
        public void afterMessageHandled(Message<?> message, MessageChannel channel,
                MessageHandler handler, Exception exception) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            if (!(handler instanceof SimpleBrokerMessageHandler)
                    || accessor.getMessageType() != SimpMessageType.SUBSCRIBE) {
                return;
            }
            String receipt = accessor.getReceipt();
            if (receipt == null) {
                return;
            }
            CompletableFuture<Void> ready = waiting.remove(receipt);
            if (ready != null) {
                if (exception == null) {
                    ready.complete(null);
                } else {
                    ready.completeExceptionally(exception);
                }
            }
        }
    }

    /** Minimal STOMP 1.2 client that sends no heartbeats or frames after subscribing. */
    private static final class SocketClient implements WebSocket.Listener {
        private final CompletableFuture<String> connected = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder received = new StringBuilder();
        private WebSocket webSocket;

        void send(String frame) throws Exception {
            assertNotNull(webSocket);
            webSocket.sendText(frame, true).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        void abort() {
            if (webSocket != null) {
                webSocket.abort();
            }
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            received.append(text);
            int end;
            while ((end = received.indexOf("\0")) >= 0) {
                String frame = received.substring(0, end).stripLeading().replace("\r\n", "\n");
                received.delete(0, end + 1);
                if (frame.startsWith("CONNECTED\n")) {
                    connected.complete(frame);
                } else if (frame.startsWith("MESSAGE\n")) {
                    messages.add(frame);
                } else if (frame.startsWith("ERROR\n")) {
                    connected.completeExceptionally(new IllegalStateException("STOMP CONNECT was rejected"));
                }
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            connected.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }
}
