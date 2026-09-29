package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import tools.jackson.databind.ObjectMapper;

/** Exercises literal encoded request targets over Tomcat with persisted sessions and real routing. */
abstract class AbstractEncodedSecurityPathIntegrationTest {
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private SessionRepository<? extends Session> sessionRepository;
    @Autowired private CookieSerializer cookieSerializer;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PrivilegedMfaProperties privilegedMfaProperties;
    @MockitoBean private PrivilegedAccountService privilegedAccountService;
    @MockitoBean private WebAuthnService webAuthnService;
    @LocalServerPort private int port;
    @Value("${server.servlet.context-path:}") private String contextPath;

    private final List<String> sessions = new ArrayList<>();
    private User account;
    private Workspace workspace;
    private String configuredEnforcement;

    @BeforeEach
    void createIsolatedMember() {
        configuredEnforcement = privilegedMfaProperties.getEnforced();
        String suffix = UUID.randomUUID().toString();
        Organization organization = new Organization();
        organization.setName("Encoded path " + suffix);
        organization.setSlug("encoded-path-" + suffix);
        organizationMapper.insert(organization);
        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Encoded path " + suffix);
        workspace.setSlug("encoded-path-" + suffix);
        workspaceMapper.insert(workspace);
        account = AuthenticatedSessions.account(userMapper, "encoded-path");
        assertNotNull(account);
        workspaceMapper.addMember(workspace.getId(), account.getId(), "member");
        when(privilegedAccountService.isPrivileged(account.getId())).thenReturn(true);
        when(webAuthnService.hasPasskey(account.getId())).thenReturn(true);
    }

    @AfterEach
    void deleteSessions() {
        privilegedMfaProperties.setEnforced(configuredEnforcement);
        sessions.forEach(sessionRepository::deleteById);
    }

    @Test
    void literalEncodedExportReturnsTheSameMfaRefusalAsCanonical() throws Exception {
        String sessionId = storeSession(false, false);
        assertEquals(200, get("/api/auth/me", sessionId).statusCode());
        HttpResponse<String> canonical = get("/api/exports/persons", sessionId);
        HttpResponse<String> encoded = get("/api/%65xports/persons", sessionId);
        assertEquals(403, canonical.statusCode());
        assertEquals(canonical.statusCode(), encoded.statusCode());
        assertEquals("RECENT_AUTHENTICATION_REQUIRED", objectMapper.readTree(canonical.body()).path("code").asString());
        assertEquals("RECENT_AUTHENTICATION_REQUIRED", objectMapper.readTree(encoded.body()).path("code").asString());
        assertNotNull(sessionRepository.findById(sessionId));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unstampedExportUsesTheSameRolloutPolicyOverHttpAndService(boolean enforced) throws Exception {
        privilegedMfaProperties.setEnforced(Boolean.toString(enforced));
        String sessionId = storeSession(false, false);
        HttpResponse<String> canonical = get("/api/exports/persons", sessionId);
        HttpResponse<String> encoded = get("/api/%65xports/persons", sessionId);
        assertEquals(enforced ? 403 : 200, canonical.statusCode());
        assertEquals(canonical.statusCode(), encoded.statusCode());
        if (enforced) {
            assertEquals("RECENT_AUTHENTICATION_REQUIRED", objectMapper.readTree(canonical.body()).path("code").asString());
            assertEquals("RECENT_AUTHENTICATION_REQUIRED", objectMapper.readTree(encoded.body()).path("code").asString());
        } else {
            assertEquals(canonical.body(), encoded.body());
            assertTrue(encoded.body().startsWith("\uFEFFid,name,email"));
        }
        assertNotNull(sessionRepository.findById(sessionId));
    }

    @Test
    void literalEncodedAuthPathInvalidatesAnAbsolutelyExpiredSession() throws Exception {
        String sessionId = storeSession(true, false);
        assertNotNull(sessionRepository.findById(sessionId));
        HttpResponse<String> response = get("/%61pi/auth/me", sessionId);
        assertEquals(401, response.statusCode());
        assertNull(sessionRepository.findById(sessionId));
        assertEquals(401, get("/api/auth/me", sessionId).statusCode());
    }

    @Test
    void freshPasskeySessionCanReachTheEncodedExportHandler() throws Exception {
        String sessionId = storeSession(false, true);
        HttpResponse<String> canonical = get("/api/exports/persons", sessionId);
        HttpResponse<String> encoded = get("/api/%65xports/persons", sessionId);
        assertEquals(200, canonical.statusCode());
        assertEquals(200, encoded.statusCode());
        assertEquals(canonical.body(), encoded.body());
        assertTrue(encoded.body().startsWith("\uFEFFid,name,email"));
    }

    @Test
    void ambiguousEncodingIsRejectedBeforeRouting() throws Exception {
        String sessionId = storeSession(false, true);
        for (String path : List.of("/api/%2565xports/persons", "/api/exports/persons%253Bx", "/api/exports%2Fpersons",
                "/api/exports%5Cpersons", "/api/%00exports/persons", "/api/%2e%2e/exports/persons")) {
            assertEquals(400, get(path, sessionId).statusCode(), path);
        }
    }

    private HttpResponse<String> get(String path, String sessionId) throws Exception {
        URI target = URI.create("http://127.0.0.1:" + port + contextPath + path);
        assertEquals(contextPath + path, target.getRawPath());
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                    .header(HttpHeaders.COOKIE, sessionCookie(sessionId))
                    .header("X-Workspace-Id", Integer.toString(workspace.getId()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String sessionCookie(String sessionId) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookieSerializer.writeCookieValue(new CookieSerializer.CookieValue(
                new MockHttpServletRequest(), response, sessionId));
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(header);
        return header.split(";", 2)[0];
    }

    private String storeSession(boolean expired, boolean steppedUp) {
        String id = storeSession(sessionRepository, expired, steppedUp);
        sessions.add(id);
        return id;
    }

    private <S extends Session> String storeSession(SessionRepository<S> repository, boolean expired, boolean steppedUp) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new UsernamePasswordAuthenticationToken(
                        account, null, account.getAuthorities())));
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, account.getSessionEpoch());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, account.getId());
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR,
                System.currentTimeMillis() - (expired ? Duration.ofHours(13).toMillis() : 0));
        if (steppedUp) {
            session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, account.getId());
            session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, System.currentTimeMillis());
        }
        repository.save(session);
        return session.getId();
    }
}
