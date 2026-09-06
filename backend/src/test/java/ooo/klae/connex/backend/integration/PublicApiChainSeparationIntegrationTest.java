package ooo.klae.connex.backend.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.ApiRequestBodySizeFilter;
import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.CorrelationIdFilter;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Security-chain separation, CSRF, CORS, and rate-limit integration contract. */
@SpringBootTest(properties = {
    "connex.public-api.enabled=true",
    "connex.public-api.rate-limit.requests=2",
    "connex.public-api.rate-limit.window-seconds=60"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicApiChainSeparationIntegrationTest {
    private static final String PASSWORD = "Public-Api-Chain-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantContext tenantContext;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new CorrelationIdFilter(), springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void cleanUpControlPlaneState() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        Throwable cleanupFailure = attempt(null, this::assertNoDedicatedPlacementLeaks);
        for (int workspaceId : workspaceIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM api_credential WHERE workspace_id = ?", workspaceId));
        }
        for (int organizationId : organizationIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM org_placement WHERE org_id = ?", organizationId));
        }
        for (int workspaceId : workspaceIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM workspace WHERE id = ?", workspaceId));
        }
        for (int organizationId : organizationIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?", organizationId));
        }
        for (int userId : userIds) {
            cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?", userId));
        }
        cleanupFailure = attempt(cleanupFailure, this::assertControlPlaneCleanupComplete);
        workspaceIds.clear();
        organizationIds.clear();
        userIds.clear();
        if (cleanupFailure != null) {
            rethrow(cleanupFailure);
        }
    }

    @Test
    void sessionCannotAuthenticatePublicAndPatCannotAuthenticatePrivate() throws Exception {
        Fixture fixture = fixture("separation");

        mockMvc.perform(get("/api/v1/me").session(fixture.session()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(get("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void publicUnsafeMethodSkipsCsrfWhilePrivateIssuanceStillRequiresIt() throws Exception {
        Fixture fixture = fixture("csrf");

        mockMvc.perform(post("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("insufficient_scope"));
        mockMvc.perform(post("/api/api-credentials")
                .session(fixture.session())
                .header("X-Workspace-Id", fixture.workspace().getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody()))
            .andExpect(status().isForbidden());
    }

    @Test
    void thirdRequestInWindowIsRateLimitedWithCompleteHeaders() throws Exception {
        Fixture fixture = fixture("rate");

        for (int request = 0; request < 2; request++) {
            mockMvc.perform(get("/api/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token()))
                .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"))
            .andExpect(header().string("X-RateLimit-Limit", "2"))
            .andExpect(header().string("X-RateLimit-Remaining", "0"))
            .andExpect(header().string("X-RateLimit-Reset", matchesPattern("[1-9][0-9]*")))
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")));
    }

    @Test
    void publicCorsAllowsAuthorizationWithoutCredentialedBrowserMode() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-correlation-id"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
    }

    @Test
    void allowedOriginInvalidTokenKeepsCorsAndCorrelationHeaders() throws Exception {
        String correlationId = "public-invalid-token-401";

        MvcResult result = mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .header(HttpHeaders.AUTHORIZATION, "Bearer cnx_pat_" + "z".repeat(43))
                .header("X-Correlation-Id", correlationId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"))
            .andExpect(jsonPath("$.error.request_id").value(correlationId))
            .andExpect(header().string("X-Correlation-Id", correlationId))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                containsString("X-Correlation-Id")))
            .andExpect(header().string(HttpHeaders.VARY, containsString(HttpHeaders.ORIGIN)))
            .andReturn();

        assertEquals(
            result.getResponse().getHeader("X-Correlation-Id"),
            objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("error").path("request_id").textValue());
    }

    @Test
    void rejectedPublicCorsRequestUsesTheStableErrorEnvelope() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                .secure(true)
                .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("invalid_cors_request"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")));
    }

    @Test
    void oversizedPublicBodyIsRejectedWithEnvelopeBeforeSecurity() throws Exception {
        Fixture fixture = fixture("oversized");
        RequestBodySizeProperties limits = new RequestBodySizeProperties();
        limits.setMaxBodyBytes(8);
        MockMvc boundedMockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                new CorrelationIdFilter(),
                new ApiRequestBodySizeFilter(limits, objectMapper),
                springSecurityFilterChain)
            .build();

        boundedMockMvc.perform(post("/api/v1/me")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("123456789"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error.code").value("request_too_large"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void firewallMethodRejectionUsesPublicEnvelopeAndKeepsBrowserDefault() throws Exception {
        mockMvc.perform(request(org.springframework.http.HttpMethod.TRACE, "/api/v1/me")
                .secure(true))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.error.code").value("method_not_allowed"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")));

        mockMvc.perform(request(org.springframework.http.HttpMethod.TRACE, "/api/tasks"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""));
    }

    @Test
    void nonMethodPublicFirewallRejectionUsesBadRequestEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/me;blocked").secure(true))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("bad_request"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")));

        mockMvc.perform(get("/api/v1;blocked/me").secure(true))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("bad_request"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")));

        mockMvc.perform(request(
                org.springframework.http.HttpMethod.GET,
                URI.create("/api/v1%2Fme")).secure(true))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("bad_request"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string(
                "Strict-Transport-Security", containsString("max-age=31536000")));

        mockMvc.perform(get("/api/tasks;x"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(""));
    }

    @Test
    void encodedQuestionMarkRemainsBrowserPlanePathData() throws Exception {
        mockMvc.perform(request(
                org.springframework.http.HttpMethod.GET,
                URI.create("/api/v1%3Ffoo")))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string(""));
    }

    private Fixture fixture(String label) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("API chain " + suffix);
        organization.setSlug("api-chain-" + label + "-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("API chain " + suffix);
        workspace.setSlug("api-chain-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        User manager = new User();
        manager.setUsername("api_chain_" + label + "_" + suffix);
        manager.setDisplayName("API chain " + label);
        manager.setEmail(label + "-" + suffix + "@example.com");
        manager.setPasswordHash(passwordEncoder.encode(PASSWORD));
        manager.setTimezone("UTC");
        userMapper.insert(manager);
        userIds.add(manager.getId());
        workspaceMapper.addMember(workspace.getId(), manager.getId(), "member");
        grantApiManager(workspace, manager, label);
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, manager);
        MockHttpSession session = login(manager.getUsername());
        PublicApiTestSecuritySupport.stepUp(session, manager.getId());
        MvcResult result = mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody()))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Fixture(workspace, session, body.path("token").textValue());
    }

    private void grantApiManager(Workspace workspace, User user, String label) {
        jdbcTemplate.update(
            "INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)",
            workspace.getId(),
            "API manager " + label + " " + UUID.randomUUID().toString().substring(0, 8));
        Integer roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM workspace_role WHERE workspace_id = ? ORDER BY id DESC LIMIT 1",
            Integer.class,
            workspace.getId());
        assertNotNull(roleId);
        jdbcTemplate.update(
            "INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?), (?, ?)",
            roleId,
            "API_CREDENTIAL_MANAGE",
            roleId,
            "REPORT_READ");
        jdbcTemplate.update(
            "UPDATE workspace_member SET role_id = ? WHERE workspace_id = ? AND user_id = ?",
            roleId,
            workspace.getId(),
            user.getId());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private static String issueBody() {
        return "{\"name\":\"Chain test\",\"scopes\":[\"crm.read\"],"
            + "\"expiresAt\":\"2099-01-01T00:00:00\"}";
    }

    private void assertNoDedicatedPlacementLeaks() {
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement "
                    + "WHERE org_id = ? AND placement_mode = 'dedicated_database'",
                Integer.class,
                organizationId),
                "Public API chain test leaked a dedicated org_placement row for organization "
                    + organizationId);
        }
    }

    private void assertControlPlaneCleanupComplete() {
        for (int workspaceId : workspaceIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
                Integer.class,
                workspaceId),
                "Public API chain test leaked api_credential rows for workspace " + workspaceId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?",
                Integer.class,
                workspaceId),
                "Public API chain test leaked workspace " + workspaceId);
        }
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement WHERE org_id = ?",
                Integer.class,
                organizationId),
                "Public API chain test leaked org_placement rows for organization "
                    + organizationId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE id = ?",
                Integer.class,
                organizationId),
                "Public API chain test leaked organization " + organizationId);
        }
        for (int userId : userIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ?",
                Integer.class,
                userId),
                "Public API chain test leaked app_user " + userId);
        }
    }

    private static Throwable attempt(Throwable previous, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (previous == null) {
                return failure;
            }
            previous.addSuppressed(failure);
        }
        return previous;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private record Fixture(Workspace workspace, MockHttpSession session, String token) {
    }
}
