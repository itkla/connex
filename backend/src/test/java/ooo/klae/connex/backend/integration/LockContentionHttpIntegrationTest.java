package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.RequestTransactionCommitListener;
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.publicapi.ApiCredential;
import ooo.klae.connex.backend.publicapi.ApiCredentialService;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.RoutingBinding;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantResolutionInterceptor;
import tools.jackson.databind.ObjectMapper;

/** Exercises retry safety through the real server, security filters and MVC interceptor chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"connex.public-api.enabled=true", "server.address=127.0.0.1", "server.servlet.context-path="})
class LockContentionHttpIntegrationTest {
    private static final String REQUEST_ID = "lock-handler-window-123";

    @LocalServerPort private int port;
    @Autowired private UserMapper users;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private OrganizationMapper organizations;
    @Autowired private ApiCredentialMapper credentials;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private SessionRepository<? extends Session> sessions;
    @Autowired private CookieSerializer cookies;
    @Autowired private RequestTransactionCommitListener commitListener;
    @Autowired private TenantResolutionInterceptor tenantInterceptor;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @MockitoSpyBean private WorkspaceService workspaceService;
    @MockitoSpyBean private ApiCredentialService credentialService;

    private final List<String> sessionIds = new ArrayList<>();
    private final AtomicBoolean commitInHandler = new AtomicBoolean();
    private final AtomicBoolean handlerReached = new AtomicBoolean();
    private final AtomicBoolean markedAfterReadOnly = new AtomicBoolean();
    private final AtomicBoolean markedAfterWrite = new AtomicBoolean();
    private final AtomicInteger writtenRows = new AtomicInteger();
    private final AtomicReference<List<HandlerInterceptor>> handlerChain = new AtomicReference<>();
    private PublicApiFixtureSupport fixtures;
    private Workspace workspace;
    private User actor;

    @BeforeEach
    void setUp() {
        fixtures = new PublicApiFixtureSupport(organizations, workspaces, users, passwordEncoder, jdbc,
            "Lock-Contention-Http-Pw1!");
        workspace = fixtures.newWorkspace("lock-window");
        User created = fixtures.newMember(workspace, "owner", "lock-window");
        actor = users.getUserById(created.getId());
        PublicApiTestSecuritySupport.enrollApprovedPasskey(jdbc, actor);
        doAnswer(invocation -> failInHandler()).when(workspaceService).getMembershipsForCurrentUser();
        doAnswer(invocation -> failInHandler()).when(credentialService).currentCredential();
    }

    @AfterEach
    void cleanUp() {
        sessionIds.forEach(sessions::deleteById);
        fixtures.cleanUp(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sessionRequestsRetryOnlyBeforeAHandlerWriteCommits(boolean commit) throws Exception {
        commitInHandler.set(commit);
        String session = persistedSession(sessions);
        MockHttpServletResponse cookie = new MockHttpServletResponse();
        cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), cookie, session));

        var response = request("/api/workspaces")
            .header("Cookie", Objects.requireNonNull(cookie.getHeader("Set-Cookie")).split(";", 2)[0])
            .header("X-Workspace-Id", Integer.toString(workspace.getId()));
        HttpResponse<String> result = send(response);

        assertEquals(commit ? 500 : 409, result.statusCode(), result.body());
        assertEquals(json.valueToTree(commit
            ? Map.of("message", "An unexpected error occurred", "correlationId", REQUEST_ID)
            : Map.of("message", "The request conflicts with another operation; try again")),
            json.readTree(result.body()));
        assertHandlerWindow(commit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void credentialBookkeepingDoesNotSuppressRetryBeforeAHandlerWriteCommits(boolean commit) throws Exception {
        commitInHandler.set(commit);
        String token = "cnx_pat_" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new SecureRandom().generateSeed(32));
        ApiCredential credential = new ApiCredential();
        credential.setWorkspaceId(workspace.getId());
        credential.setOrganizationId(workspace.getOrgId());
        credential.setCreatedById(actor.getId());
        credential.setMembershipId(Objects.requireNonNull(
            workspaces.getMembershipGenerationId(workspace.getId(), actor.getId())));
        credential.setName("Lock window");
        credential.setTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8))));
        credential.setTokenLast4(token.substring(token.length() - 4));
        credential.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        credentials.insert(credential);
        credentials.insertScopes(credential.getId(), List.of("crm.read"));
        AtomicBoolean bookkeepingCommitted = new AtomicBoolean();
        AtomicBoolean markedBeforeHandler = new AtomicBoolean();
        doAnswer(invocation -> {
            Object authenticated = invocation.callRealMethod();
            TransactionTemplate bookkeeping = new TransactionTemplate(transactions);
            bookkeeping.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            bookkeeping.executeWithoutResult(status -> bookkeepingCommitted.set(
                credentialService.recordSuccessfulUse(credential.getId(), credential.getTokenHash())));
            markedBeforeHandler.set(RequestTransactionCommitListener.committedDuringRequest());
            return authenticated;
        }).when(credentialService).authenticate(any(RoutingBinding.class));

        HttpResponse<String> result = send(request("/api/v1/me").header("Authorization", "Bearer " + token));

        assertEquals(commit ? 500 : 409, result.statusCode(), result.body());
        assertEquals(json.valueToTree(Map.of("error", Map.of(
            "code", commit ? "internal_error" : "conflict",
            "message", commit ? "An unexpected error occurred"
                : "The request conflicts with another operation; try again",
            "request_id", REQUEST_ID))), json.readTree(result.body()));
        assertNotNull(credentials.findByIdWithScopes(workspace.getId(), credential.getId()).getLastUsedAt(),
            "credential bookkeeping must have executed its real last-used write");
        assertTrue(bookkeepingCommitted.get());
        assertFalse(markedBeforeHandler.get());
        assertHandlerWindow(commit);
    }

    @Test
    void contentionInTenantResolutionDoesNotEnterTheHandlerWindow() throws Exception {
        doThrow(contention()).when(workspaceService).getRole(workspace.getId(), actor.getId());
        String session = persistedSession(sessions);
        MockHttpServletResponse cookie = new MockHttpServletResponse();
        cookies.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), cookie, session));

        HttpResponse<String> result = send(request("/api/workspaces")
            .header("Cookie", Objects.requireNonNull(cookie.getHeader("Set-Cookie")).split(";", 2)[0])
            .header("X-Workspace-Id", Integer.toString(workspace.getId())));

        assertEquals(409, result.statusCode(), result.body());
        assertFalse(handlerReached.get());
        verify(workspaceService, never()).getMembershipsForCurrentUser();
    }

    private Object failInHandler() throws Exception {
        handlerReached.set(true);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        handlerChain.set(List.copyOf(Objects.requireNonNull(mappings.getHandler(attributes.getRequest()))
            .getInterceptorList()));
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> jdbc.queryForObject("SELECT 1", Integer.class));
        markedAfterReadOnly.set(RequestTransactionCommitListener.committedDuringRequest());
        if (commitInHandler.get()) {
            TransactionTemplate writable = new TransactionTemplate(transactions);
            writable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            writable.executeWithoutResult(status -> writtenRows.set(jdbc.update(
                "UPDATE app_user SET display_name = ? WHERE id = ?", "Handler committed", actor.getId())));
        }
        markedAfterWrite.set(RequestTransactionCommitListener.committedDuringRequest());
        throw contention();
    }

    private void assertHandlerWindow(boolean commit) {
        assertTrue(handlerReached.get());
        assertFalse(markedAfterReadOnly.get());
        assertEquals(commit, markedAfterWrite.get());
        assertEquals(commit ? 1 : 0, writtenRows.get());
        if (commit) {
            assertEquals("Handler committed", users.getUserById(actor.getId()).getDisplayName());
        }
        List<HandlerInterceptor> chain = Objects.requireNonNull(handlerChain.get());
        assertSame(commitListener, chain.getLast(), "the handler window must follow every actual MVC interceptor");
        assertEquals(chain.size() - 1, chain.indexOf(commitListener));
        assertTrue(chain.indexOf(tenantInterceptor) >= 0);
        assertTrue(chain.indexOf(tenantInterceptor) < chain.indexOf(commitListener));
    }

    private <S extends Session> String persistedSession(SessionRepository<S> repository) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            new SecurityContextImpl(new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities())));
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, System.currentTimeMillis());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, actor.getId());
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, actor.getSessionEpoch());
        repository.save(session);
        sessionIds.add(session.getId());
        return session.getId();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(45)).header(CorrelationIds.HEADER_NAME, REQUEST_ID).GET();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static CannotAcquireLockException contention() {
        return new CannotAcquireLockException("Lock contention", new SQLException("Lock contention", "HY000", 1205));
    }
}
