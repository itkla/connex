package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.publicapi.ApiCredential;
import ooo.klae.connex.backend.services.UserDeletionTransaction;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Database-backed public credential authentication and management contract. */
@SpringBootTest(properties = "connex.public-api.enabled=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PublicApiCredentialIntegrationTest.StatementCountConfiguration.class)
class PublicApiCredentialIntegrationTest {
    private static final String PASSWORD = "Public-Api-Credential-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserDeletionTransaction userDeletionTransaction;
    @Autowired private ApiCredentialMapper apiCredentialMapper;
    @Autowired private StatementCounter statementCounter;
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
            .addFilters(springSecurityFilterChain)
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
    void validCredentialReturnsMetadataWhileOnlyHashAndLast4Persist(CapturedOutput output)
            throws Exception {
        Workspace workspace = newWorkspace("happy");
        User manager = newMember(workspace, "member", "happy");
        grantApiManager(workspace, manager, "happy");
        MockHttpSession session = loginWithStepUp(manager);
        String credentialName = "caller-label-" + UUID.randomUUID();

        Issued issued = issue(
            session, workspace, credentialName, "crm.read", "activities.read");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialId").value(issued.id()))
            .andExpect(jsonPath("$.name").value(credentialName))
            .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
            .andExpect(jsonPath("$.organizationId").value(workspace.getOrgId()))
            .andExpect(jsonPath("$.scopes[0]").value("crm.read"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        String storedHash = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM api_credential WHERE id = ?", String.class, issued.id());
        String last4 = jdbcTemplate.queryForObject(
            "SELECT token_last4 FROM api_credential WHERE id = ?", String.class, issued.id());
        assertNotNull(storedHash);
        assertEquals(sha256Hex(issued.token()), storedHash);
        assertNotEquals(issued.token(), storedHash);
        assertEquals(issued.token().substring(issued.token().length() - 4), last4);
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'api_credential.issue' AND changes LIKE ?",
            Integer.class,
            "%" + issued.token() + "%"));
        assertAuditRowsExclude(issued.token(), credentialName);
        assertFalse(output.getAll().contains(issued.token()));
        assertFalse(output.getAll().contains(credentialName));

        mockMvc.perform(get("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].last4").value(last4))
            .andExpect(jsonPath("$[0].token").doesNotExist())
            .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void headOnReadRouteReturnsGetRepresentationAndSecurityHeaders() throws Exception {
        Workspace workspace = newWorkspace("head");
        User manager = newMember(workspace, "member", "head");
        grantApiManager(workspace, manager, "head");
        Issued issued = issue(
            loginWithStepUp(manager), workspace, "HEAD route", "crm.read");
        MvcResult getResult = mockMvc.perform(get("/api/v1/me")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isOk())
            .andReturn();

        mockMvc.perform(head("/api/v1/me")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isOk())
            .andExpect(header().string(
                HttpHeaders.CONTENT_TYPE,
                getResult.getResponse().getHeader(HttpHeaders.CONTENT_TYPE)))
            .andExpect(header().string(
                HttpHeaders.CACHE_CONTROL,
                getResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)))
            .andExpect(header().string(
                "Content-Security-Policy",
                getResult.getResponse().getHeader("Content-Security-Policy")))
            .andExpect(header().string(
                "X-Content-Type-Options",
                getResult.getResponse().getHeader("X-Content-Type-Options")))
            .andExpect(header().string(
                "X-Frame-Options",
                getResult.getResponse().getHeader("X-Frame-Options")))
            .andExpect(header().string(
                "Referrer-Policy",
                getResult.getResponse().getHeader("Referrer-Policy")))
            .andExpect(header().string(
                "Strict-Transport-Security",
                getResult.getResponse().getHeader("Strict-Transport-Security")))
            .andExpect(header().string(
                "X-RateLimit-Limit",
                getResult.getResponse().getHeader("X-RateLimit-Limit")))
            .andExpect(header().string(
                "X-RateLimit-Reset",
                getResult.getResponse().getHeader("X-RateLimit-Reset")))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void mapperReloadsExactlyTwoScalarScopeValues() throws Exception {
        Workspace workspace = newWorkspace("scalar-scopes");
        User manager = newMember(workspace, "member", "scalar-scopes");
        grantApiManager(workspace, manager, "scalar-scopes");
        Issued issued = issue(
            loginWithStepUp(manager),
            workspace,
            "Scalar scopes",
            "crm.read",
            "activities.read");

        ApiCredential reloaded = apiCredentialMapper.findByTokenHash(sha256Hex(issued.token()));

        assertNotNull(reloaded);
        assertEquals(List.of("activities.read", "crm.read"), reloaded.getScopes());
    }

    @Test
    void singleCredentialScopeLoaderRequiresTheParentWorkspace() throws Exception {
        Workspace workspace = newWorkspace("scoped-child");
        Workspace otherWorkspace = newWorkspace("scoped-child-other");
        User manager = newMember(workspace, "member", "scoped-child");
        grantApiManager(workspace, manager, "scoped-child");
        Issued issued = issue(
            loginWithStepUp(manager),
            workspace,
            "Scoped child",
            "crm.read",
            "activities.read");

        assertEquals(
            List.of("activities.read", "crm.read"),
            apiCredentialMapper.findScopes(workspace.getId(), issued.id()));
        assertEquals(
            List.of(),
            apiCredentialMapper.findScopes(otherWorkspace.getId(), issued.id()));
    }

    @Test
    void credentialInventoryUsesOneBoundedWorkspaceScopedScopeBatch() throws Exception {
        Workspace workspace = newWorkspace("batched-list");
        User manager = newMember(workspace, "member", "batched-list");
        grantApiManager(workspace, manager, "batched-list");
        MockHttpSession session = loginWithStepUp(manager);
        issue(session, workspace, "Batch one", "crm.read");
        issue(session, workspace, "Batch two", "crm.read", "activities.read");
        issue(session, workspace, "Batch three", "activities.read");
        statementCounter.reset();

        mockMvc.perform(get("/api/api-credentials")
                .queryParam("page", "1")
                .queryParam("size", "2")
                .session(session)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        assertEquals(1, statementCounter.count("ApiCredentialMapper.listByWorkspace"));
        assertEquals(1, statementCounter.count("ApiCredentialMapper.findScopesByCredentialIds"));
        assertEquals(0, statementCounter.count("ApiCredentialMapper.findScopes"));

        mockMvc.perform(get("/api/api-credentials")
                .queryParam("page", "1")
                .queryParam("size", "101")
                .session(session)
                .header("X-Workspace-Id", workspace.getId()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedUnknownExpiredAndRevokedCredentialsReturnTheSamePublic401() throws Exception {
        Workspace workspace = newWorkspace("refusal");
        User manager = newMember(workspace, "member", "refusal");
        grantApiManager(workspace, manager, "refusal");
        MockHttpSession session = loginWithStepUp(manager);
        Issued expired = issue(session, workspace, "Expired", "crm.read");
        jdbcTemplate.update(
            "UPDATE api_credential SET expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?",
            expired.id());
        Issued revoked = issue(session, workspace, "Revoked", "crm.read");
        mockMvc.perform(delete("/api/api-credentials/{id}", revoked.id())
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        assertInvalid(null);
        assertInvalid("Basic abc");
        assertInvalid("Bearer ");
        assertInvalid("Bearer wrong_prefix_" + "a".repeat(43));
        assertInvalid("Bearer cnx_pat_" + "a".repeat(43));
        assertInvalid("Bearer " + expired.token());
        assertInvalid("Bearer " + revoked.token());
        mockMvc.perform(get("/api/v1/me")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + expired.token(),
                    "Bearer " + revoked.token()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    @Test
    void revokedCredentialWithUnavailableDedicatedPlacementReturnsInvalidTokenBeforeRouting()
            throws Exception {
        Workspace workspace = newWorkspace("revoked-unavailable-placement");
        User manager = newMember(workspace, "member", "revoked-unavailable-placement");
        grantApiManager(workspace, manager, "revoked-unavailable-placement");
        MockHttpSession session = loginWithStepUp(manager);
        Issued issued = issue(session, workspace, "Revoked unavailable placement", "crm.read");
        mockMvc.perform(delete("/api/api-credentials/{id}", issued.id())
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        try {
            jdbcTemplate.update(
                "INSERT INTO org_placement (org_id, placement_mode, database_handle) VALUES (?, ?, ?)",
                workspace.getOrgId(),
                "dedicated_database",
                "cnx_" + UUID.randomUUID().toString().replace("-", ""));

            mockMvc.perform(get("/api/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_token"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        } finally {
            tenantContext.clear();
            RequestContextHolder.resetRequestAttributes();
            jdbcTemplate.update(
                "DELETE FROM org_placement WHERE org_id = ?", workspace.getOrgId());
        }
    }

    @Test
    void memberWithoutManagementPermissionCannotIssueCredential() throws Exception {
        Workspace workspace = newWorkspace("member");
        User member = newMember(workspace, "member", "member");
        MockHttpSession session = login(member.getUsername());

        mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody("Forbidden", "crm.read")))
            .andExpect(status().isForbidden());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void stalePasswordOnlySessionCannotIssueCredential() throws Exception {
        Workspace workspace = newWorkspace("stale-step-up");
        User manager = newMember(workspace, "member", "stale-step-up");
        grantApiManager(workspace, manager, "stale-step-up");
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, manager);
        MockHttpSession session = login(manager.getUsername());

        mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody("Stale session", "crm.read")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void privilegedCreatorWithoutEnrollmentGetsEnrollmentRequired401() throws Exception {
        Workspace workspace = newWorkspace("enrollment");
        User manager = newMember(workspace, "member", "enrollment");
        grantApiManager(workspace, manager, "enrollment");
        MockHttpSession session = loginWithStepUp(manager);
        Issued issued = issue(session, workspace, "Enrollment", "crm.read");
        jdbcTemplate.update("DELETE FROM webauthn_user_entity WHERE user_id = ?", manager.getId());

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code")
                .value("privileged_mfa_enrollment_required"))
            .andExpect(jsonPath("$.error.message").value(
                "A passkey must be enrolled before this privileged account can continue"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ? AND last_used_at IS NOT NULL",
            Integer.class,
            issued.id()));
    }

    @Test
    void credentialNameCannotContainAPastedPat() throws Exception {
        Workspace workspace = newWorkspace("name-secret");
        User manager = newMember(workspace, "member", "name-secret");
        grantApiManager(workspace, manager, "name-secret");
        MockHttpSession session = loginWithStepUp(manager);

        mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody("copied cnx_pat_" + "a".repeat(43), "crm.read")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void accountErasureDeletesCredentialRows() throws Exception {
        Workspace workspace = newWorkspace("erasure");
        User manager = newMember(workspace, "member", "erasure");
        grantApiManager(workspace, manager, "erasure");
        Issued issued = issue(loginWithStepUp(manager), workspace, "Erasure", "crm.read");

        userDeletionTransaction.reserve(manager.getId(), "public-api-erasure-test");
        userDeletionTransaction.delete(manager.getId(), "public-api-erasure-test");

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ?", Integer.class, issued.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential_scope WHERE credential_id = ?",
            Integer.class,
            issued.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'api_credential.account_erased'"
                + " AND workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void accountErasureDeletesCredentialsRevokedByTheAccount() throws Exception {
        Workspace workspace = newWorkspace("revoker-erasure");
        User revoker = newMember(workspace, "member", "revoker-erasure");
        User creator = newMember(workspace, "member", "revoked-creator");
        grantApiManager(workspace, revoker, "revoker-erasure");
        grantApiManager(workspace, creator, "revoked-creator");
        Issued issued = issue(
            loginWithStepUp(creator), workspace, "Revoker erasure", "crm.read");

        mockMvc.perform(delete("/api/api-credentials/{id}", issued.id())
                .session(loginWithStepUp(revoker))
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        assertEquals("SET NULL", jdbcTemplate.queryForObject(
            "SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'fk_api_credential_revoker'",
            String.class));

        userDeletionTransaction.reserve(revoker.getId(), "public-api-revoker-erasure-test");
        userDeletionTransaction.delete(revoker.getId(), "public-api-revoker-erasure-test");

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ?", Integer.class, issued.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'api_credential.account_erased'"
                + " AND workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void accountErasureDeletesRevokerCredentialWhileWorkspaceIsTearingDown() throws Exception {
        Workspace workspace = newWorkspace("tearing-down-revoker-erasure");
        User revoker = newMember(workspace, "member", "tearing-down-revoker");
        User creator = newMember(workspace, "member", "tearing-down-creator");
        grantApiManager(workspace, revoker, "tearing-down-revoker");
        grantApiManager(workspace, creator, "tearing-down-creator");
        Issued issued = issue(
            loginWithStepUp(creator), workspace, "Tearing-down revoker", "crm.read");
        mockMvc.perform(delete("/api/api-credentials/{id}", issued.id())
                .session(loginWithStepUp(revoker))
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        assertEquals(1, jdbcTemplate.update(
            "UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?",
            workspace.getId()));

        userDeletionTransaction.reserve(
            revoker.getId(), "pubapi-teardown-revoker-erasure");
        userDeletionTransaction.delete(
            revoker.getId(), "pubapi-teardown-revoker-erasure");

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, revoker.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ?", Integer.class, issued.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased'"
                + " AND workspace_id = ? AND org_id = ?",
            Integer.class,
            workspace.getId(),
            workspace.getOrgId()));
    }

    @Test
    void revocationWritesBothRevocationColumnsTogether() throws Exception {
        Workspace workspace = newWorkspace("paired-revocation");
        User manager = newMember(workspace, "member", "paired-revocation");
        grantApiManager(workspace, manager, "paired-revocation");
        MockHttpSession session = loginWithStepUp(manager);
        Issued issued = issue(
            session, workspace, "Paired revocation", "crm.read");

        mockMvc.perform(delete("/api/api-credentials/{id}", issued.id())
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential"
                + " WHERE id = ? AND revoked_at IS NOT NULL AND revoked_by_id = ?",
            Integer.class,
            issued.id(),
            manager.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential"
                + " WHERE revoked_by_id IS NOT NULL AND revoked_at IS NULL",
            Integer.class));
    }

    @Test
    void crossRevokerAccountErasuresConvergeWithoutDeadlock() throws Exception {
        Workspace workspace = newWorkspace("cross-revoker");
        User firstUser = newMember(workspace, "member", "cross-revoker-first");
        User secondUser = newMember(workspace, "member", "cross-revoker-second");
        grantApiManager(workspace, firstUser, "cross-revoker-first");
        grantApiManager(workspace, secondUser, "cross-revoker-second");
        MockHttpSession firstSession = loginWithStepUp(firstUser);
        MockHttpSession secondSession = loginWithStepUp(secondUser);
        Issued firstCredential = issue(
            firstSession, workspace, "First cross-revoker", "crm.read");
        Issued secondCredential = issue(
            secondSession, workspace, "Second cross-revoker", "crm.read");

        mockMvc.perform(delete("/api/api-credentials/{id}", firstCredential.id())
                .session(secondSession)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/api-credentials/{id}", secondCredential.id())
                .session(firstSession)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());

        String firstOwner = "cross-revoker-first-delete";
        String secondOwner = "cross-revoker-second-delete";
        userDeletionTransaction.reserve(firstUser.getId(), firstOwner);
        userDeletionTransaction.reserve(secondUser.getId(), secondOwner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstDeletion = executor.submit(() -> {
                ready.countDown();
                start.await();
                userDeletionTransaction.delete(firstUser.getId(), firstOwner);
                return null;
            });
            Future<?> secondDeletion = executor.submit(() -> {
                ready.countDown();
                start.await();
                userDeletionTransaction.delete(secondUser.getId(), secondOwner);
                return null;
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            firstDeletion.get(10, TimeUnit.SECONDS);
            secondDeletion.get(10, TimeUnit.SECONDS);
        }

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id IN (?, ?)",
            Integer.class,
            firstUser.getId(),
            secondUser.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id IN (?, ?)",
            Integer.class,
            firstCredential.id(),
            secondCredential.id()));
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'api_credential.account_erased' "
                + "AND workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void crossOrganizationRevokerErasuresConvergeWithoutDeadlock() throws Exception {
        Workspace workspaceInFirstOrg = newWorkspace("cross-org-first");
        Workspace workspaceInSecondOrg = newWorkspace("cross-org-second");
        User firstAccount = newMember(
            workspaceInSecondOrg, "member", "cross-org-first-account");
        User secondAccount = newMember(
            workspaceInFirstOrg, "member", "cross-org-second-account");
        User firstCreator = newMember(
            workspaceInFirstOrg, "member", "cross-org-first-creator");
        User secondCreator = newMember(
            workspaceInSecondOrg, "member", "cross-org-second-creator");
        User firstCoOwner = newMember(
            workspaceInFirstOrg, "member", "cross-org-first-co-owner");
        User secondCoOwner = newMember(
            workspaceInSecondOrg, "member", "cross-org-second-co-owner");
        grantApiManager(workspaceInSecondOrg, firstAccount, "cross-org-first-account");
        grantApiManager(workspaceInFirstOrg, secondAccount, "cross-org-second-account");
        grantApiManager(workspaceInFirstOrg, firstCreator, "cross-org-first-creator");
        grantApiManager(workspaceInSecondOrg, secondCreator, "cross-org-second-creator");
        jdbcTemplate.update(
            "INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')",
            workspaceInFirstOrg.getOrgId(),
            firstAccount.getId());
        jdbcTemplate.update(
            "INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')",
            workspaceInFirstOrg.getOrgId(),
            firstCoOwner.getId());
        jdbcTemplate.update(
            "INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')",
            workspaceInSecondOrg.getOrgId(),
            secondAccount.getId());
        jdbcTemplate.update(
            "INSERT INTO org_member (org_id, user_id, org_role) VALUES (?, ?, 'owner')",
            workspaceInSecondOrg.getOrgId(),
            secondCoOwner.getId());
        Issued firstCredential = issue(
            loginWithStepUp(firstCreator),
            workspaceInFirstOrg,
            "First crossed organization credential",
            "crm.read");
        Issued secondCredential = issue(
            loginWithStepUp(secondCreator),
            workspaceInSecondOrg,
            "Second crossed organization credential",
            "crm.read");
        mockMvc.perform(delete("/api/api-credentials/{id}", secondCredential.id())
                .session(loginWithStepUp(firstAccount))
                .header("X-Workspace-Id", workspaceInSecondOrg.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/api-credentials/{id}", firstCredential.id())
                .session(loginWithStepUp(secondAccount))
                .header("X-Workspace-Id", workspaceInFirstOrg.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace_member"
                + " WHERE role = 'owner' AND user_id IN (?, ?)",
            Integer.class,
            firstAccount.getId(),
            secondAccount.getId()));

        String firstOwner = "cross-org-first-delete";
        String secondOwner = "cross-org-second-delete";
        userDeletionTransaction.reserve(firstAccount.getId(), firstOwner);
        userDeletionTransaction.reserve(secondAccount.getId(), secondOwner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Throwable firstFailure;
        Throwable secondFailure;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> firstDeletion = executor.submit(() -> {
                ready.countDown();
                start.await();
                return captureFailure(() -> userDeletionTransaction.delete(
                    firstAccount.getId(), firstOwner));
            });
            Future<Throwable> secondDeletion = executor.submit(() -> {
                ready.countDown();
                start.await();
                return captureFailure(() -> userDeletionTransaction.delete(
                    secondAccount.getId(), secondOwner));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            firstFailure = firstDeletion.get(20, TimeUnit.SECONDS);
            secondFailure = secondDeletion.get(20, TimeUnit.SECONDS);
        }

        assertNull(firstFailure);
        assertNull(secondFailure);
        assertFalse(hasDeadlockCause(firstFailure));
        assertFalse(hasDeadlockCause(secondFailure));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id IN (?, ?)",
            Integer.class,
            firstAccount.getId(),
            secondAccount.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id IN (?, ?)",
            Integer.class,
            firstCredential.id(),
            secondCredential.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased' AND workspace_id = ?",
            Integer.class,
            workspaceInFirstOrg.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased' AND workspace_id = ?",
            Integer.class,
            workspaceInSecondOrg.getId()));
    }

    @Test
    void crossedResolvedTenantContextErasuresConvergeWithoutDeadlock() throws Exception {
        Workspace firstWorkspace = newWorkspace("crossed-context-first");
        Workspace secondWorkspace = newWorkspace("crossed-context-second");
        User firstAccount = newMember(firstWorkspace, "member", "crossed-context-first");
        User secondAccount = newMember(secondWorkspace, "member", "crossed-context-second");
        workspaceMapper.addMember(secondWorkspace.getId(), firstAccount.getId(), "member");
        workspaceMapper.addMember(firstWorkspace.getId(), secondAccount.getId(), "member");
        grantApiManager(secondWorkspace, firstAccount, "crossed-context-first");
        grantApiManager(firstWorkspace, secondAccount, "crossed-context-second");
        Issued firstCredential = issue(
            loginWithStepUp(firstAccount), secondWorkspace, "Crossed context first", "crm.read");
        Issued secondCredential = issue(
            loginWithStepUp(secondAccount), firstWorkspace, "Crossed context second", "crm.read");

        String firstOwner = "crossed-context-first-delete";
        String secondOwner = "crossed-context-second-delete";
        userDeletionTransaction.reserve(firstAccount.getId(), firstOwner);
        userDeletionTransaction.reserve(secondAccount.getId(), secondOwner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Throwable firstFailure;
        Throwable secondFailure;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> firstDeletion = executor.submit(() -> eraseAs(
                firstAccount, firstWorkspace, firstOwner, ready, start));
            Future<Throwable> secondDeletion = executor.submit(() -> eraseAs(
                secondAccount, secondWorkspace, secondOwner, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            firstFailure = firstDeletion.get(20, TimeUnit.SECONDS);
            secondFailure = secondDeletion.get(20, TimeUnit.SECONDS);
        }

        assertNull(firstFailure);
        assertNull(secondFailure);
        assertFalse(hasDeadlockCause(firstFailure));
        assertFalse(hasDeadlockCause(secondFailure));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id IN (?, ?)",
            Integer.class,
            firstAccount.getId(),
            secondAccount.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id IN (?, ?)",
            Integer.class,
            firstCredential.id(),
            secondCredential.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased' AND workspace_id = ?",
            Integer.class,
            secondWorkspace.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased' AND workspace_id = ?",
            Integer.class,
            firstWorkspace.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'user.delete' AND workspace_id = ? AND actor_label = ?",
            Integer.class,
            firstWorkspace.getId(),
            firstAccount.getDisplayName()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'user.delete' AND workspace_id = ? AND actor_label = ?",
            Integer.class,
            secondWorkspace.getId(),
            secondAccount.getDisplayName()));
    }

    @Test
    void erasureRacingAForeignWorkspaceRevokeConvergesWithoutDeadlock() throws Exception {
        Workspace currentWorkspace = newWorkspace("racing-revoke-current");
        Workspace credentialWorkspace = newWorkspace("racing-revoke-credential");
        User erasedAccount = newMember(currentWorkspace, "member", "racing-revoke-erased");
        User otherManager = newMember(credentialWorkspace, "member", "racing-revoke-manager");
        workspaceMapper.addMember(credentialWorkspace.getId(), erasedAccount.getId(), "member");
        grantApiManager(credentialWorkspace, erasedAccount, "racing-revoke-erased");
        grantApiManager(credentialWorkspace, otherManager, "racing-revoke-manager");
        Issued erasedCredential = issue(
            loginWithStepUp(erasedAccount), credentialWorkspace, "Racing erased", "crm.read");
        MockHttpSession managerSession = loginWithStepUp(otherManager);
        Issued foreignCredential = issue(
            managerSession, credentialWorkspace, "Racing foreign", "crm.read");

        String owner = "racing-revoke-delete";
        userDeletionTransaction.reserve(erasedAccount.getId(), owner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Throwable erasureFailure;
        Throwable revokeFailure;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Throwable> erasure = executor.submit(() -> eraseAs(
                erasedAccount, currentWorkspace, owner, ready, start));
            Future<Throwable> revocation = executor.submit(() -> {
                ready.countDown();
                start.await();
                return captureFailure(() -> mockMvc.perform(
                        delete("/api/api-credentials/{id}", foreignCredential.id())
                            .session(managerSession)
                            .header("X-Workspace-Id", credentialWorkspace.getId())
                            .with(csrf().asHeader()))
                    .andExpect(status().isNoContent()));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            erasureFailure = erasure.get(20, TimeUnit.SECONDS);
            revokeFailure = revocation.get(20, TimeUnit.SECONDS);
        }

        assertNull(erasureFailure);
        assertNull(revokeFailure);
        assertFalse(hasDeadlockCause(erasureFailure));
        assertFalse(hasDeadlockCause(revokeFailure));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id = ?",
            Integer.class,
            erasedAccount.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ?",
            Integer.class,
            erasedCredential.id()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential"
                + " WHERE id = ? AND revoked_at IS NOT NULL AND revoked_by_id = ?",
            Integer.class,
            foreignCredential.id(),
            otherManager.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'api_credential.account_erased' AND workspace_id = ?",
            Integer.class,
            credentialWorkspace.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'user.delete' AND workspace_id = ?",
            Integer.class,
            currentWorkspace.getId()));
    }

    @Test
    void managerCannotIssueAScopeOutsideLiveRbac() throws Exception {
        Workspace workspace = newWorkspace("scope-ceiling");
        User manager = newMember(workspace, "member", "scope-ceiling");
        grantApiManager(workspace, manager, "scope-ceiling");
        MockHttpSession session = loginWithStepUp(manager);

        mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody("Excessive", "crm.write")))
            .andExpect(status().isForbidden());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void realWorkspaceDeletionExecutesCredentialCascade() throws Exception {
        Workspace workspace = newWorkspace("workspace-cascade");
        User manager = newMember(workspace, "member", "workspace-cascade");
        grantApiManager(workspace, manager, "workspace-cascade");
        Issued issued = issue(
            loginWithStepUp(manager), workspace, "Workspace cascade", "crm.read");
        String deleteRule = jdbcTemplate.queryForObject(
            "SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_api_credential_workspace'",
            String.class);
        assertEquals("CASCADE", deleteRule);
        assertEquals(1, jdbcTemplate.update(
            "DELETE FROM workspace WHERE id = ?", workspace.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE id = ?", Integer.class, issued.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential_scope WHERE credential_id = ?",
            Integer.class,
            issued.id()));
    }

    @Test
    void revocationCommittedBeforeAuthorizationSnapshotIsDenied() throws Exception {
        Workspace workspace = newWorkspace("revoked-before-snapshot");
        User manager = newMember(workspace, "member", "revoked-before-snapshot");
        grantApiManager(workspace, manager, "revoked-before-snapshot");
        Issued issued = issue(
            loginWithStepUp(manager), workspace, "Revoked before snapshot", "crm.read");

        assertEquals(1, jdbcTemplate.update(
            "UPDATE api_credential SET revoked_at = UTC_TIMESTAMP(6), revoked_by_id = ? WHERE id = ?",
            manager.getId(),
            issued.id()));

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    @Test
    void revocationCommittedAfterSnapshotIsBoundedToThatRequestTransaction() throws Exception {
        Workspace workspace = newWorkspace("revoked-after-snapshot");
        User manager = newMember(workspace, "member", "revoked-after-snapshot");
        grantApiManager(workspace, manager, "revoked-after-snapshot");
        Issued issued = issue(
            loginWithStepUp(manager), workspace, "Revoked after snapshot", "crm.read");
        CountDownLatch snapshotEstablished = new CountDownLatch(1);
        CountDownLatch continueRequest = new CountDownLatch(1);
        Filter snapshotBarrier = (request, response, chain) -> {
            if (request instanceof HttpServletRequest httpRequest
                    && "/api/v1/me".equals(httpRequest.getRequestURI())) {
                snapshotEstablished.countDown();
                try {
                    if (!continueRequest.await(10, TimeUnit.SECONDS)) {
                        throw new ServletException("Timed out waiting to continue public API request");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ServletException("Interrupted while waiting to continue request", exception);
                }
            }
            chain.doFilter(request, response);
        };
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain, snapshotBarrier)
            .build();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> request = executor.submit(() -> mockMvc.perform(
                    get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
                .andReturn());
            try {
                assertTrue(snapshotEstablished.await(10, TimeUnit.SECONDS));
                Future<Integer> revocation = executor.submit(() -> jdbcTemplate.update(
                    "UPDATE api_credential SET revoked_at = UTC_TIMESTAMP(6), revoked_by_id = ? "
                        + "WHERE id = ?",
                    manager.getId(),
                    issued.id()));
                assertEquals(1, revocation.get(5, TimeUnit.SECONDS));
                assertFalse(request.isDone());
            } finally {
                continueRequest.countDown();
            }

            assertEquals(200, request.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        }

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + issued.token()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    private void assertInvalid(String authorization) throws Exception {
        var request = get("/api/v1/me");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        mockMvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private Issued issue(MockHttpSession session, Workspace workspace, String name, String... scopes)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody(name, scopes)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith("cnx_pat_")))
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Issued(body.path("credential").path("id").longValue(), body.path("token").textValue());
    }

    private static String issueBody(String name, String... scopes) {
        String scopeJson = java.util.Arrays.stream(scopes)
            .map(scope -> "\"" + scope + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        return "{\"name\":\"" + name + "\",\"scopes\":[" + scopeJson
            + "],\"expiresAt\":\"2099-01-01T00:00:00\"}";
    }

    private Workspace newWorkspace(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Public API " + label + " " + suffix);
        organization.setSlug("public-api-" + label + "-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Public API " + label + " " + suffix);
        workspace.setSlug("public-api-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newMember(Workspace workspace, String role, String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("public_api_" + label + "_" + suffix);
        user.setDisplayName("Public API " + label);
        user.setEmail(label + "-" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        workspaceMapper.addMember(workspace.getId(), user.getId(), role);
        return user;
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

    private MockHttpSession loginWithStepUp(User user) throws Exception {
        PublicApiTestSecuritySupport.enrollPasskey(jdbcTemplate, user);
        MockHttpSession session = login(user.getUsername());
        PublicApiTestSecuritySupport.stepUp(session, user.getId());
        return session;
    }

    private void assertAuditRowsExclude(String token, String credentialName) {
        for (var row : jdbcTemplate.queryForList(
                "SELECT * FROM audit_log WHERE action LIKE 'api_credential.%'")) {
            for (Object value : row.values()) {
                String rendered = value instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(value);
                assertFalse(rendered.contains(token));
                assertFalse(rendered.contains(credentialName));
            }
        }
    }

    private void assertNoDedicatedPlacementLeaks() {
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement "
                    + "WHERE org_id = ? AND placement_mode = 'dedicated_database'",
                Integer.class,
                organizationId),
                "Public API credential test leaked a dedicated org_placement row for organization "
                    + organizationId);
        }
    }

    private void assertControlPlaneCleanupComplete() {
        for (int workspaceId : workspaceIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
                Integer.class,
                workspaceId),
                "Public API credential test leaked api_credential rows for workspace " + workspaceId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?",
                Integer.class,
                workspaceId),
                "Public API credential test leaked workspace " + workspaceId);
        }
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement WHERE org_id = ?",
                Integer.class,
                organizationId),
                "Public API credential test leaked org_placement rows for organization "
                    + organizationId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE id = ?",
                Integer.class,
                organizationId),
                "Public API credential test leaked organization " + organizationId);
        }
        for (int userId : userIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ?",
                Integer.class,
                userId),
                "Public API credential test leaked app_user " + userId);
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

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Throwable eraseAs(
            User account,
            Workspace currentWorkspace,
            String owner,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities()));
        tenantContext.set(
            currentWorkspace.getId(), currentWorkspace.getOrgId(), account.getId(), "member", null);
        try {
            ready.countDown();
            start.await();
            return captureFailure(
                () -> userDeletionTransaction.delete(account.getId(), owner));
        } finally {
            tenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static Throwable captureFailure(FailingAction action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static boolean hasDeadlockCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && (sqlException.getErrorCode() == 1213
                        || "40001".equals(sqlException.getSQLState()))) {
                return true;
            }
            if (cause.getMessage() != null
                    && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface FailingAction {
        void run() throws Exception;
    }

    private record Issued(long id, String token) {
    }

    @Intercepts({
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                CacheKey.class, BoundSql.class})
    })
    static final class StatementCounter implements Interceptor {
        private static final String MAPPER_PREFIX =
            "ooo.klae.connex.backend.mappers.ApiCredentialMapper.";

        private final Map<String, Integer> counts = new ConcurrentHashMap<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().startsWith(MAPPER_PREFIX)) {
                counts.merge(statement.getId().substring(MAPPER_PREFIX.length()), 1, Integer::sum);
            }
            return invocation.proceed();
        }

        void reset() {
            counts.clear();
        }

        int count(String statement) {
            int separator = statement.lastIndexOf('.');
            String localName = separator < 0 ? statement : statement.substring(separator + 1);
            return counts.getOrDefault(localName, 0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StatementCountConfiguration {
        @Bean
        StatementCounter statementCounter() {
            return new StatementCounter();
        }
    }
}
