package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.flywaydb.core.Flyway;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowBackfillRunner;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;

/**
 * Runs real security, handlers, services and mappers against a minimal control catalog with no
 * tenant tables. Only the unrelated startup rule backfill is replaced: it enumerates tenant rules
 * before any HTTP request. The normal Flyway initializer migrates the source schema before the listed control tables are copied,
 * so selecting this class alone does not depend on any earlier test.
 */
@SpringBootTest(properties = {
    "connex.tenancy.routing.mode=catalog-per-placement",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.signature.enabled=false"
})
@Import(ControlOnlyHandlerRoutingIntegrationTest.ScratchControlConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ControlOnlyHandlerRoutingIntegrationTest {
    private static final List<String> CONTROL_TABLES = List.of(
        "app_user", "organization", "org_member", "org_placement", "workspace", "workspace_member",
        "workspace_role", "workspace_role_permission", "audit_log", "audit_log_integrity_head",
        "secret_value", "workspace_mail_config", "sso_connection", "webauthn_user_entity",
        "webauthn_credential", "privileged_credential_attestation", "object_storage_backend_identity",
        "client_error");
    private static String sourceUrl;
    private static String sourceCatalog;
    private static String username;
    private static String password;
    private static String controlCatalog;
    private static boolean controlCatalogCreated;

    @Autowired private WebApplicationContext application;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter security;
    @Autowired private UserMapper users;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgPlacementMapper placements;
    @Autowired private TenantCatalogResolver catalogs;
    @Autowired private TenantContext tenantContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @Autowired private TagMapper tags;
    @MockitoSpyBean private WorkspaceService workspaceService;
    @MockitoBean private LegacyWorkflowBackfillRunner startupRuleBackfill;

    private final List<String> tenantCatalogs = new ArrayList<>();
    private MockMvc http;
    private User user;
    private Workspace unavailable;
    private Workspace available;
    private Workspace pending;

    @DynamicPropertySource
    static void controlOnlyDatabase(DynamicPropertyRegistry properties) throws SQLException {
        sourceUrl = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assertNotNull(sourceUrl, "CONNEX_DB_URL is required for control-only HTTP routing tests");
        assertNotNull(username, "CONNEX_DB_USERNAME is required for control-only HTTP routing tests");
        assertNotNull(password, "CONNEX_DB_PASSWORD is required for control-only HTTP routing tests");
        sourceCatalog = TenantRoutingConfig.databaseFromJdbcUrl(sourceUrl);
        assertNotNull(sourceCatalog, "The source JDBC URL must name a catalog");
        controlCatalog = "connexdb_routing_control_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(sourceUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + controlCatalog);
            controlCatalogCreated = true;
        }
        properties.add("spring.datasource.url", () -> sourceUrl.replace(
            "/" + sourceCatalog, "/" + controlCatalog));
        properties.add("connex.tenancy.routing.default-catalog", () -> controlCatalog);
        properties.add("spring.flyway.url", () -> sourceUrl);
        properties.add("spring.flyway.user", () -> username);
        properties.add("spring.flyway.password", () -> password);
    }

    /** Migrates the CI schema normally before copying only control tables into the HTTP catalog. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScratchControlConfiguration {
        @Bean
        FlywayMigrationStrategy migrateThenCloneControlTables() {
            return flyway -> {
                flyway.migrate();
                try (Connection connection = DriverManager.getConnection(sourceUrl, username, password);
                        Statement statement = connection.createStatement()) {
                    for (String table : CONTROL_TABLES) {
                        assertTrue(TablePlaneRegistry.CONTROL_PLANE_TABLES.contains(table), table);
                        statement.execute("CREATE TABLE " + controlCatalog + "." + table
                            + " LIKE " + sourceCatalog + "." + table);
                    }
                } catch (SQLException failure) {
                    throw new IllegalStateException("Cannot prepare the control-only scratch catalog", failure);
                }
            };
        }
    }

    @AfterAll
    static void dropControlDatabase() throws SQLException {
        if (controlCatalogCreated) {
            try (Connection connection = DriverManager.getConnection(sourceUrl, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + controlCatalog);
                controlCatalogCreated = false;
            }
        }
    }

    @BeforeEach
    void setUp() {
        clearContexts();
        http = MockMvcBuilders.webAppContextSetup(application).addFilters(security).build();
        user = AuthenticatedSessions.account(users, "control-only");
        unavailable = workspace("Unavailable");
        available = workspace("Available");
        pending = workspace("Pending");
        workspaces.addMember(unavailable.getId(), user.getId(), "member");
        workspaces.addMember(available.getId(), user.getId(), "member");
        workspaces.addPendingMember(pending.getId(), user.getId(), "member");
        workspaces.setLastActiveWorkspaceId(user.getId(), unavailable.getId());
        placement(unavailable, "connex_operated_silo", null);
    }

    @AfterEach
    void clearContexts() {
        if (tenantContext != null) {
            tenantContext.clear();
        }
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        for (String catalog : tenantCatalogs) {
            jdbc.execute("DROP DATABASE " + catalog);
        }
        tenantCatalogs.clear();
    }

    @Test
    void normalFlywayPreparesTheSourceBeforeTheScratchCatalogIsUsed() {
        assertNotNull(flyway.info().current());
        assertNoTenantTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"listing", "pending", "switch", "profile", "update", "locale",
        "listing-head", "pending-head", "profile-head"})
    void eachReviewedHandlerWorksWithAnUnservableRememberedPlacementAndNoTenantTables(String operation)
            throws Exception {
        assertNoTenantTables();
        assertThrows(ServiceUnavailableException.class, () -> catalogs.resolveCatalog(unavailable.getOrgId()));

        http.perform(authenticated(operation(operation))).andExpect(status().isOk());

        assertFalse(tenantContext.isResolved());
        assertFalse(tenantContext.hasControlSelection());
        RequestContextHolder.resetRequestAttributes();
        assertEquals(operation.equals("switch") ? available.getId() : unavailable.getId(),
            workspaces.getLastActiveWorkspaceId(user.getId()));
        if (operation.equals("update") || operation.equals("locale")) {
            String action = operation.equals("update") ? "user.update" : "user.updateLocale";
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND actor_id = ? AND workspace_id = ? AND org_id = ?",
                Integer.class, action, user.getId(), unavailable.getId(), unavailable.getOrgId()));
        }
    }

    @Test
    void listingAndPendingMembershipsKeepLabelsOrderingAndActiveSelection() throws Exception {
        http.perform(authenticated(get("/api/workspaces")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeWorkspaceId").value(unavailable.getId()))
            .andExpect(jsonPath("$.workspaces.length()").value(2))
            .andExpect(jsonPath("$.workspaces[0].id").value(available.getId()))
            .andExpect(jsonPath("$.workspaces[0].name").value(available.getName()))
            .andExpect(jsonPath("$.workspaces[1].id").value(unavailable.getId()));
        http.perform(authenticated(get("/api/workspaces/pending")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(pending.getId()))
            .andExpect(jsonPath("$[0].name").value(pending.getName()));
    }

    @Test
    void aFutureTenantReadInAReviewedServiceIsRefusedByTheInstalledRuntimeGuard() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            tags.getAllTags(unavailable.getId());
            return invocation.callRealMethod();
        }).when(workspaceService).getMembershipsForCurrentUser();

        Exception failure = http.perform(authenticated(get("/api/workspaces")))
            .andExpect(status().is5xxServerError()).andReturn().getResolvedException();

        assertNotNullFailure(failure);
    }

    @Test
    void profileTargetAuthorizationAndImmutableEmailRemainIntact() throws Exception {
        User other = AuthenticatedSessions.account(users, "control-outsider");
        http.perform(authenticated(get("/api/users/" + other.getId())))
            .andExpect(status().isNotFound());
        http.perform(authenticated(put("/api/users/" + other.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(profileBody(other))))
            .andExpect(status().isForbidden());
        http.perform(authenticated(operation("update")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(user.getEmail()))
            .andExpect(jsonPath("$.displayName").value("Updated profile"));
    }

    @Test
    void headerSelectionSuppliesProfileAuthorizationAndAuditRatherThanTheRememberedWorkspace() throws Exception {
        http.perform(authenticated(operation("locale"))
                .header("X-Workspace-Id", available.getId())
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(available.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locale").value("ja"));
        RequestContextHolder.resetRequestAttributes();

        assertEquals(unavailable.getId(), workspaces.getLastActiveWorkspaceId(user.getId()));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'user.updateLocale' AND actor_id = ? AND workspace_id = ? AND org_id = ?",
            Integer.class, user.getId(), available.getId(), available.getOrgId()));
    }

    @Test
    void aCookieOnlySelectionControlsMemberProfileVisibility() throws Exception {
        User sibling = AuthenticatedSessions.account(users, "control-sibling");
        workspaces.addMember(available.getId(), sibling.getId(), "member");

        http.perform(authenticated(get("/api/users/" + sibling.getId()))
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(available.getId()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(sibling.getId()));
        http.perform(authenticated(get("/api/users/" + sibling.getId())))
            .andExpect(status().isNotFound());
    }

    @Test
    void profileRenameKeepsItsAfterCommitSocketClosurePathControlOnly() throws Exception {
        String renamed = user.getUsername() + "-renamed";
        http.perform(authenticated(put("/api/users/" + user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(profileBody(user).replace(user.getUsername(), renamed))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(renamed));
        http.perform(authenticated(get("/api/auth/me")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(renamed));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aValidExplicitUnavailablePinCanSwitchWithoutRedirectingItsTarget(boolean matchingCookie)
            throws Exception {
        http.perform(authenticated(operation("switch"))
                .header("X-Workspace-Id", unavailable.getId())
                .cookie(new Cookie(WorkspaceCookie.NAME,
                    Integer.toString(matchingCookie ? unavailable.getId() : available.getId()))))
            .andExpect(status().isOk())
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(available.getId())));
    }

    @Test
    void switchStillAuthorizesItsOwnPathTarget() throws Exception {
        Workspace foreign = workspace("Foreign");
        http.perform(authenticated(post("/api/workspaces/" + foreign.getId() + "/switch")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value(
                "User " + user.getId() + " is not a member of this workspace"));
        RequestContextHolder.resetRequestAttributes();
        assertEquals(unavailable.getId(), workspaces.getLastActiveWorkspaceId(user.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tags", "/api/notifications", "/api/notifications/counts",
        "/api/notifications/facets", "/api/permissions", "/api/users"})
    void tenantInboxAndUnreviewedHandlersKeepTheir503(String path) throws Exception {
        http.perform(authenticated(get(path))).andExpect(status().isServiceUnavailable());
    }

    @Test
    void timezoneCreationAndOffboardingRemainOutsideTheAllowlist() throws Exception {
        http.perform(authenticated(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"UTC\"}"))).andExpect(status().isServiceUnavailable());
        http.perform(authenticated(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Must not provision\"}"))).andExpect(status().isServiceUnavailable());
        http.perform(authenticated(post("/api/workspaces/" + unavailable.getId() + "/leave")))
            .andExpect(status().isServiceUnavailable());
        http.perform(authenticated(post("/api/workspaces/" + pending.getId() + "/decline")))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void malformedRepeatedAndForeignPinsRetainTheirRefusals() throws Exception {
        http.perform(authenticated(get("/api/workspaces")).header("X-Workspace-Id", "invalid"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace invalid"));
        http.perform(authenticated(get("/api/workspaces"))
                .header("X-Workspace-Id", unavailable.getId(), unavailable.getId()))
            .andExpect(status().isForbidden());
        http.perform(authenticated(get("/api/workspaces")).header("X-Workspace-Id", pending.getId()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace " + pending.getId()));
        http.perform(authenticated(get("/api/tags")).header("X-Workspace-Id", unavailable.getId()))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void authProfileRemainsExcludedAndAuthenticationCsrfAndSessionEpochStayEnforced() throws Exception {
        http.perform(authenticated(get("/api/auth/me")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        http.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
        http.perform(operation("switch").session(AuthenticatedSessions.stampedSession(user))
                .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()))))
            .andExpect(status().isForbidden());
        http.perform(authenticated(operation("switch")).with(csrf().asHeader().useInvalidToken()))
            .andExpect(status().isForbidden());
        users.bumpSessionEpoch(user.getId());
        http.perform(authenticated(get("/api/workspaces"))).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSelectionStillHealsSafeReadsAndRefusesUnsafeProfileUpdates() throws Exception {
        workspaces.removeMember(unavailable.getId(), user.getId());
        http.perform(authenticated(operation("locale")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Not a member of workspace " + unavailable.getId()));
        http.perform(authenticated(get("/api/workspaces")))
            .andExpect(status().isOk())
            .andExpect(cookie().value(WorkspaceCookie.NAME, Integer.toString(available.getId())))
            .andExpect(jsonPath("$.activeWorkspaceId").value(available.getId()));
        RequestContextHolder.resetRequestAttributes();
        assertEquals(available.getId(), workspaces.getLastActiveWorkspaceId(user.getId()));
    }

    @Test
    void switchingAwayRestoresTenantRoutingToAMinimalCatalogWithoutControlTables() throws Exception {
        String catalog = "connexdb_routing_control_tags_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + catalog);
        tenantCatalogs.add(catalog);
        jdbc.execute("CREATE TABLE " + catalog + ".tag LIKE " + sourceCatalog + ".tag");
        jdbc.update("INSERT INTO " + catalog + ".tag (workspace_id, name, color) VALUES (?, 'Routed tag', '#123456')",
            available.getId());
        jdbc.update("INSERT INTO " + catalog + ".tag (workspace_id, name, color) VALUES (?, 'Hidden tag', '#654321')",
            unavailable.getId());
        placement(available, "dedicated_database", catalog);

        http.perform(authenticated(get("/api/tags"))).andExpect(status().isServiceUnavailable());
        http.perform(authenticated(operation("switch"))).andExpect(status().isOk());
        http.perform(authenticated(get("/api/tags"))
                .header("X-Workspace-Id", available.getId())
                .cookie(new Cookie(WorkspaceCookie.NAME, Integer.toString(available.getId()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Routed tag"));
        RequestContextHolder.resetRequestAttributes();
        List<String> tables = jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", String.class, catalog);
        assertEquals(List.of("tag"), tables);
        assertTrue(Collections.disjoint(tables, TablePlaneRegistry.CONTROL_PLANE_TABLES));
    }

    private void assertNoTenantTables() {
        List<String> tables = jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?", String.class, controlCatalog);
        assertEquals(new TreeSet<>(CONTROL_TABLES), new TreeSet<>(tables));
        assertTrue(Collections.disjoint(tables, TablePlaneRegistry.ORG_DATA_TABLES));
    }

    private Workspace workspace(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName(prefix + " organization " + suffix);
        organization.setSlug("control-" + suffix);
        organizations.insert(organization);
        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName(prefix + " " + suffix);
        workspace.setSlug("control-workspace-" + suffix);
        workspaces.insert(workspace);
        return workspace;
    }

    private void placement(Workspace workspace, String mode, String handle) {
        OrgPlacement placement = OrgPlacement.sharedDefault(workspace.getOrgId());
        placement.setPlacementMode(mode);
        placement.setDatabaseHandle(handle == null ? "cnx_unavailable_" + workspace.getId() : handle);
        placements.insert(placement);
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.session(AuthenticatedSessions.stampedSession(user))
            .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
            .with(csrf().asHeader());
    }

    private MockHttpServletRequestBuilder operation(String name) {
        return switch (name) {
            case "listing" -> get("/api/workspaces");
            case "pending" -> get("/api/workspaces/pending");
            case "switch" -> post("/api/workspaces/" + available.getId() + "/switch");
            case "profile" -> get("/api/users/" + user.getId());
            case "update" -> put("/api/users/" + user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content(profileBody(user));
            case "locale" -> patch("/api/users/me/locale").contentType(MediaType.APPLICATION_JSON)
                .content("{\"locale\":\"ja\"}");
            case "listing-head" -> head("/api/workspaces");
            case "pending-head" -> head("/api/workspaces/pending");
            case "profile-head" -> head("/api/users/" + user.getId());
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static String profileBody(User target) {
        return "{\"username\":\"" + target.getUsername() + "\",\"displayName\":\"Updated profile\","
            + "\"email\":\"ignored@example.com\"}";
    }

    private static void assertNotNullFailure(Throwable failure) {
        assertTrue(failure != null, "The controller must not hide a rejected tenant statement");
        while (failure != null && !(failure instanceof ServiceUnavailableException)) {
            assertFalse(failure instanceof SQLException, "The mapper must be refused before SQL execution");
            failure = failure.getCause();
        }
        assertTrue(failure instanceof ServiceUnavailableException,
            "The physical-plane guard must be the cause of the refusal");
        assertEquals("Tenant SQL is unavailable during control-only handling", failure.getMessage());
    }

}
