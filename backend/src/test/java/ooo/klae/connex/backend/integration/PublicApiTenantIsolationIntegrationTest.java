package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-workspace and live-membership refusal tests for public credentials.
 * A successful request resolves placement exactly once in the authentication filter, and MVC
 * retains that authoritative tenant identity and catalog for the controller read.
 */
@SpringBootTest(properties = "connex.public-api.enabled=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicApiTenantIsolationIntegrationTest {
    private static final String PASSWORD = "Public-Api-Tenant-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private WorkspaceService workspaceService;
    @MockitoSpyBean private TenantCatalogResolver tenantCatalogResolver;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<String> scratchCatalogs = new ArrayList<>();

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "connex.tenancy.routing.mode",
            () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add(
            "connex.tenancy.routing.default-catalog",
            PublicApiTenantIsolationIntegrationTest::defaultCatalog);
    }

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        clearInvocations(tenantCatalogResolver);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void cleanUpRoutingState() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        Throwable cleanupFailure = null;
        try {
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
        } finally {
            for (String catalog : scratchCatalogs) {
                cleanupFailure = attempt(cleanupFailure, () -> jdbcTemplate.execute(
                    "DROP DATABASE IF EXISTS `" + identifier(catalog) + "`"));
            }
        }
        cleanupFailure = attempt(cleanupFailure, this::assertCleanupComplete);
        workspaceIds.clear();
        organizationIds.clear();
        userIds.clear();
        scratchCatalogs.clear();
        clearInvocations(tenantCatalogResolver);
        if (cleanupFailure != null) {
            rethrow(cleanupFailure);
        }
    }

    @Test
    void patKeepsOneAuthoritativePlacementWhenASecondResolutionWouldDrift() throws Exception {
        Workspace bound = newWorkspace("bound");
        Workspace other = newWorkspace("other");
        User manager = newUser("binding");
        workspaceMapper.addMember(bound.getId(), manager.getId(), "member");
        workspaceMapper.addMember(other.getId(), manager.getId(), "member");
        grantApiManager(bound, manager, "binding");
        MockHttpSession session = loginWithStepUp(manager);
        String token = issue(session, bound, "crm.read");
        String boundCatalog = createScratchCatalog("bound");
        String otherCatalog = createScratchCatalog("other");
        insertPlacement(bound.getOrgId(), boundCatalog);
        insertPlacement(other.getOrgId(), otherCatalog);
        doReturn(boundCatalog, otherCatalog)
            .when(tenantCatalogResolver).resolveCatalog(bound.getOrgId());
        clearInvocations(tenantCatalogResolver);

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Workspace-Id", other.getId())
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(other.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceId").value(bound.getId()))
            .andExpect(jsonPath("$.organizationId").value(bound.getOrgId()))
            .andExpect(jsonPath("$.scopes[0]").value("crm.read"));

        verify(tenantCatalogResolver, times(1)).resolveCatalog(bound.getOrgId());
        verify(tenantCatalogResolver, never()).resolveCatalog(other.getOrgId());
    }

    @Test
    void removingCreatorMembershipRejectsTheVeryNextRequest() throws Exception {
        Workspace workspace = newWorkspace("membership");
        User manager = newUser("membership");
        workspaceMapper.addMember(workspace.getId(), manager.getId(), "member");
        grantApiManager(workspace, manager, "membership");
        MockHttpSession session = loginWithStepUp(manager);
        String token = issue(session, workspace, "crm.read");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk());
        workspaceMapper.removeMember(workspace.getId(), manager.getId());
        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"));
    }

    @Test
    void removalAndFreshReinviteCannotReactivateOldCredential() throws Exception {
        Workspace workspace = newWorkspace("reinvite");
        User actor = newUser("removal-actor");
        User target = newUser("reinvited-target");
        workspaceMapper.addMember(workspace.getId(), actor.getId(), "member");
        workspaceMapper.addMember(workspace.getId(), target.getId(), "member");
        grantCustomRole(workspace, actor, "member-manager", "MEMBER_MANAGE");
        grantApiManager(workspace, target, "reinvited-target");
        MockHttpSession targetSession = loginWithStepUp(target);
        String oldToken = issue(targetSession, workspace, "crm.read");
        Long oldMembershipId = jdbcTemplate.queryForObject(
            "SELECT membership_id FROM api_credential WHERE created_by_id = ? AND workspace_id = ?",
            Long.class,
            target.getId(),
            workspace.getId());
        MockHttpSession actorSession = loginWithStepUp(actor);

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                workspace.getId(), target.getId())
                .session(actorSession)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader()))
            .andExpect(status().isNoContent());
        workspaceService.ensureActiveMember(workspace.getId(), target.getId(), "member");
        grantApiManager(workspace, target, "reinvited-new-generation");
        PublicApiTestSecuritySupport.stepUp(targetSession, target.getId());
        String newToken = issue(targetSession, workspace, "crm.read");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("invalid_token"));
        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken))
            .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential WHERE created_by_id = ? AND workspace_id = ?",
            Integer.class,
            target.getId(),
            workspace.getId()));
        Long newMembershipId = jdbcTemplate.queryForObject(
            "SELECT membership_id FROM api_credential WHERE created_by_id = ? AND workspace_id = ?",
            Long.class,
            target.getId(),
            workspace.getId());
        assertNotNull(oldMembershipId);
        assertNotNull(newMembershipId);
        assertTrue(!oldMembershipId.equals(newMembershipId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'api_credential.membership_removed'"
                + " AND workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void roleDowngradeBelowEveryMappedPermissionBlocksTheVeryNextRequest() throws Exception {
        Workspace workspace = newWorkspace("role");
        User manager = newUser("role");
        workspaceMapper.addMember(workspace.getId(), manager.getId(), "member");
        grantApiManager(workspace, manager, "role");
        MockHttpSession session = loginWithStepUp(manager);
        String token = issue(session, workspace, "crm.read");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scopes").isArray())
            .andExpect(jsonPath("$.scopes.length()").value(1))
            .andExpect(jsonPath("$.scopes[0]").value("crm.read"));
        Timestamp baselineLastUsedAt = jdbcTemplate.queryForObject(
            "SELECT last_used_at FROM api_credential WHERE created_by_id = ? AND workspace_id = ?",
            Timestamp.class,
            manager.getId(),
            workspace.getId());
        assertNotNull(baselineLastUsedAt);

        grantCustomRole(workspace, manager, "no-api-permissions");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("insufficient_scope"))
            .andExpect(jsonPath("$.error.message")
                .value("The credential cannot access this resource"))
            .andExpect(jsonPath("$.error.request_id").isString())
            .andExpect(jsonPath("$.scopes").doesNotExist())
            .andExpect(jsonPath("$.credentialId").doesNotExist())
            .andExpect(header().exists("X-RateLimit-Limit"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_credential"
                + " WHERE created_by_id = ? AND workspace_id = ? AND revoked_at IS NULL",
            Integer.class,
            manager.getId(),
            workspace.getId()));
        assertEquals(baselineLastUsedAt, jdbcTemplate.queryForObject(
            "SELECT last_used_at FROM api_credential WHERE created_by_id = ? AND workspace_id = ?",
            Timestamp.class,
            manager.getId(),
            workspace.getId()));

        grantApiManager(workspace, manager, "role-restored");
        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scopes[0]").value("crm.read"));
    }

    @Test
    void partialRoleDowngradeNarrowsScopesWithoutKillingTheCredential() throws Exception {
        Workspace workspace = newWorkspace("partial-role");
        User manager = newUser("partial-role");
        workspaceMapper.addMember(workspace.getId(), manager.getId(), "member");
        grantCustomRole(
            workspace,
            manager,
            "api-issuer",
            "API_CREDENTIAL_MANAGE",
            "REPORT_READ",
            "TASK_CREATE");
        MockHttpSession session = loginWithStepUp(manager);
        String token = issue(session, workspace, "crm.read", "activities.write");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scopes.length()").value(2))
            .andExpect(jsonPath("$.scopes[0]").value("crm.read"))
            .andExpect(jsonPath("$.scopes[1]").value("activities.write"));

        grantCustomRole(workspace, manager, "tasks-only", "TASK_CREATE");

        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialId").isNumber())
            .andExpect(jsonPath("$.workspaceId").value(workspace.getId()))
            .andExpect(jsonPath("$.organizationId").value(workspace.getOrgId()))
            .andExpect(jsonPath("$.scopes.length()").value(1))
            .andExpect(jsonPath("$.scopes[0]").value("activities.write"));

        grantCustomRole(workspace, manager, "nothing");
        mockMvc.perform(get("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("insufficient_scope"));
    }

    private String issue(
            MockHttpSession session, Workspace workspace, String... scopes) throws Exception {
        String scopeJson = Arrays.stream(scopes)
            .map(scope -> "\"" + scope + "\"")
            .collect(Collectors.joining(","));
        MvcResult result = mockMvc.perform(post("/api/api-credentials")
                .session(session)
                .header("X-Workspace-Id", workspace.getId())
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tenant test\",\"scopes\":[" + scopeJson
                    + "],\"expiresAt\":\"2099-01-01T00:00:00\"}"))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("token").textValue();
    }

    private Workspace newWorkspace(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("API tenant " + label + " " + suffix);
        organization.setSlug("api-tenant-" + label + "-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("API tenant " + label + " " + suffix);
        workspace.setSlug("api-tenant-" + label + "-" + suffix);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newUser(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("api_tenant_" + label + "_" + suffix);
        user.setDisplayName("API tenant " + label);
        user.setEmail(label + "-" + suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
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

    private void grantCustomRole(
            Workspace workspace,
            User user,
            String label,
            String... permissions) {
        jdbcTemplate.update(
            "INSERT INTO workspace_role (workspace_id, name) VALUES (?, ?)",
            workspace.getId(),
            label + " " + UUID.randomUUID().toString().substring(0, 8));
        Integer roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM workspace_role WHERE workspace_id = ? ORDER BY id DESC LIMIT 1",
            Integer.class,
            workspace.getId());
        assertNotNull(roleId);
        for (String permission : permissions) {
            jdbcTemplate.update(
                "INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES (?, ?)",
                roleId,
                permission);
        }
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

    private String createScratchCatalog(String label) {
        String catalog = "cnx_public_api_" + label + "_"
            + UUID.randomUUID().toString().replace("-", "");
        scratchCatalogs.add(catalog);
        jdbcTemplate.execute("CREATE DATABASE `" + identifier(catalog)
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        return catalog;
    }

    private void insertPlacement(int organizationId, String catalog) {
        jdbcTemplate.update(
            "INSERT INTO org_placement (org_id, placement_mode, database_handle) VALUES (?, ?, ?)",
            organizationId,
            "dedicated_database",
            catalog);
    }

    private void assertCleanupComplete() {
        for (int workspaceId : workspaceIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_credential WHERE workspace_id = ?",
                Integer.class,
                workspaceId));
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?",
                Integer.class,
                workspaceId));
        }
        for (int organizationId : organizationIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM org_placement WHERE org_id = ?",
                Integer.class,
                organizationId),
                "Public API tenant test leaked org_placement rows for organization "
                    + organizationId);
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE id = ?",
                Integer.class,
                organizationId));
        }
        for (int userId : userIds) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ?",
                Integer.class,
                userId),
                "Public API tenant test leaked app_user " + userId);
        }
        for (String catalog : scratchCatalogs) {
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                Integer.class,
                catalog));
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

    private static String defaultCatalog() {
        String catalog = TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL"));
        if (catalog != null) {
            return catalog;
        }
        String configured = System.getenv("CONNEX_DB_NAME");
        return configured != null ? configured : "connexdb";
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid test catalog identifier");
        }
        return value;
    }

}
