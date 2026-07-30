package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import jakarta.servlet.Filter;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.IdentityBackfillRunner;
import ooo.klae.connex.backend.services.IdentityBackfillTransaction;
import ooo.klae.connex.backend.services.LegacyWorkflowBackfillRunner;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Proves backfill and HTTP collision reads honor placement-selected tenant catalogs.
 */
@SpringBootTest
class IdentityCollisionPlaneRoutingIntegrationTest {

    private static final String PASSWORD = "Identity-Routing-Pw1!";

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private OrgPlacementMapper orgPlacementMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PersonMapper personMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private IdentityBackfillTransaction backfillTransaction;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private TenantContext tenantContext;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private IdentityBackfillRunner identityBackfillRunner;
    @MockitoBean private LegacyWorkflowBackfillRunner legacyWorkflowBackfillRunner;

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
            IdentityCollisionPlaneRoutingIntegrationTest::defaultCatalog);
    }

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(springSecurityFilterChain)
            .build();
    }

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        tenantWorkScope.withCatalog(null, () -> {
            for (int workspaceId : workspaceIds) {
                jdbcTemplate.update(
                    "DELETE FROM identity_collision WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM person_identity WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM company_identity WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM person WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM company WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ?",
                    workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM workspace WHERE id = ?",
                    workspaceId);
            }
            for (int organizationId : organizationIds) {
                jdbcTemplate.update(
                    "DELETE FROM org_placement WHERE org_id = ?",
                    organizationId);
                jdbcTemplate.update(
                    "DELETE FROM organization WHERE id = ?",
                    organizationId);
            }
            for (int userId : userIds) {
                jdbcTemplate.update(
                    "DELETE FROM notification_recipient_state WHERE recipient_id = ?",
                    userId);
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
            }
            return null;
        });
        for (String scratchCatalog : scratchCatalogs) {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS `" + identifier(scratchCatalog) + "`");
        }
    }

    @Test
    void dedicatedPlacementRoutesBuiltInRoleReadAcrossBothCatalogs() throws Exception {
        ControlFixture fixture = newControlFixture("dedicated");
        String scratchCatalog = "cnx_identity_route_" + compactUuid();
        prepareDedicatedCollision(fixture, scratchCatalog);

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .header("X-Workspace-Id", fixture.workspace().getId())
                .session(login(fixture.user().getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].normalizedValue")
                .value("routed@example.com"))
            .andExpect(jsonPath("$.items[0].collisionSize").value(2))
            .andExpect(content().string(
                Matchers.not(Matchers.containsString("Default Decoy"))));

        tenantWorkScope.withCatalog(null, () -> {
            assertEquals(
                0,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM person_identity WHERE workspace_id = ?",
                    Integer.class,
                    fixture.workspace().getId()));
            return null;
        });
        assertFalse(tableExists(scratchCatalog, "workspace"));
        assertFalse(tableExists(scratchCatalog, "app_user"));
        assertEquals(defaultCatalog(), currentCatalog());
    }

    @Test
    void dedicatedPlacementRoutesCustomRolePermissionReadToControlCatalog() throws Exception {
        ControlFixture fixture = newControlFixture("custom-role");
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(fixture.workspace().getId());
        role.setName("Identity report reader " + compactUuid().substring(0, 8));
        tenantWorkScope.withCatalog(null, () -> {
            roleMapper.insertRole(role);
            roleMapper.insertPermissions(
                fixture.workspace().getId(),
                role.getId(),
                List.of("REPORT_READ"));
            workspaceMapper.setMemberCustomRole(
                fixture.workspace().getId(),
                fixture.user().getId(),
                role.getId());
            return null;
        });
        String scratchCatalog = "cnx_identity_role_" + compactUuid();
        prepareDedicatedCollision(fixture, scratchCatalog);

        mockMvc.perform(get("/api/identity-collisions")
                .queryParam("recordType", "person")
                .queryParam("kind", "email")
                .header("X-Workspace-Id", fixture.workspace().getId())
                .session(login(fixture.user().getUsername())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].normalizedValue")
                .value("routed@example.com"));
        assertEquals(defaultCatalog(), currentCatalog());
    }

    @Test
    void dedicatedTransactionRollsBackTenantAndControlWritesTogether() throws SQLException {
        ControlFixture fixture = newControlFixture("rollback");
        String scratchCatalog = "cnx_identity_rollback_" + compactUuid();
        scratchCatalogs.add(scratchCatalog);
        createScratchCatalog(scratchCatalog);
        insertPlacement(fixture.organization().getId(), "dedicated_database", scratchCatalog);
        String marker = "Rollback Probe " + compactUuid().substring(0, 8);

        tenantWorkScope.withWorkspacePlacement(
            fixture.workspace().getId(),
            (orgId, catalog) -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                assertThrows(
                    RollbackProbe.class,
                    () -> transaction.executeWithoutResult(status -> {
                        Person person = new Person();
                        person.setWorkspaceId(fixture.workspace().getId());
                        person.setName(marker);
                        person.setEmail("rollback@example.com");
                        personMapper.insert(person);
                        assertEquals(
                            1,
                            workspaceMapper.updateMemberRole(
                                fixture.workspace().getId(),
                                fixture.user().getId(),
                                "admin"));
                        throw new RollbackProbe();
                    }));
                return null;
            });

        tenantWorkScope.withCatalog(scratchCatalog, () -> {
            assertEquals(
                0,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM person WHERE workspace_id = ? AND name = ?",
                    Integer.class,
                    fixture.workspace().getId(),
                    marker));
            return null;
        });
        tenantWorkScope.withCatalog(null, () -> {
            assertEquals(
                "member",
                workspaceMapper.getRole(
                    fixture.workspace().getId(),
                    fixture.user().getId()));
            return null;
        });
        assertEquals(defaultCatalog(), currentCatalog());
    }

    @Test
    void dedicatedPlacementRoutesMixedMapperControlStatements() throws SQLException {
        ControlFixture fixture = newControlFixture("mixed-control");
        String scratchCatalog = "cnx_identity_mixed_" + compactUuid();
        scratchCatalogs.add(scratchCatalog);
        createScratchCatalog(scratchCatalog);
        insertPlacement(
            fixture.organization().getId(),
            "dedicated_database",
            scratchCatalog);

        tenantWorkScope.withWorkspacePlacement(
            fixture.workspace().getId(),
            (orgId, catalog) -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    assertEquals(
                        scratchCatalog,
                        jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
                    assertEquals(
                        0L,
                        notificationMapper.getStateVersion(fixture.user().getId()));
                    assertEquals(
                        1,
                        notificationMapper.bumpStateVersions(
                            List.of(fixture.user().getId())));
                    assertEquals(
                        1L,
                        notificationMapper.getStateVersion(fixture.user().getId()));
                    assertEquals(
                        List.of(fixture.workspace().getId()),
                        notificationMapper.lockRecipientMemberships(
                            fixture.user().getId()));
                    assertEquals(
                        List.of(fixture.user().getId()),
                        notificationMapper.findWorkspaceRecipientIds(
                            fixture.workspace().getId()));
                    assertEquals(
                        scratchCatalog,
                        jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
                });
                return null;
            });

        assertFalse(tableExists(scratchCatalog, "notification_recipient_state"));
        assertFalse(tableExists(scratchCatalog, "workspace_member"));
        assertEquals(defaultCatalog(), currentCatalog());
    }

    private void prepareDedicatedCollision(
            ControlFixture fixture,
            String scratchCatalog) {
        scratchCatalogs.add(scratchCatalog);
        createScratchCatalog(scratchCatalog);
        List<Person> decoys = tenantWorkScope.withCatalog(
            null,
            () -> List.of(
                newPerson(fixture.workspace(), "Default Decoy One", "decoy-one@example.net"),
                newPerson(fixture.workspace(), "Default Decoy Two", "decoy-two@example.net")));
        seedScratchPerson(
            scratchCatalog,
            fixture.workspace().getId(),
            decoys.getFirst().getId(),
            "Routed Alpha",
            "Routed@Example.com");
        seedScratchPerson(
            scratchCatalog,
            fixture.workspace().getId(),
            decoys.get(1).getId(),
            "Routed Beta",
            "routed@example.com");
        insertPlacement(fixture.organization().getId(), "dedicated_database", scratchCatalog);

        tenantWorkScope.withWorkspacePlacement(
            fixture.workspace().getId(),
            (orgId, catalog) -> {
                assertEquals(fixture.organization().getId(), orgId);
                assertEquals(scratchCatalog, catalog);
                assertEquals(
                    2,
                    backfillTransaction.backfillPersonPage(
                        catalog, fixture.workspace().getId(), 0, 500).identitiesCreated());
                assertEquals(
                    0,
                    backfillTransaction.backfillCompanyPage(
                        catalog, fixture.workspace().getId(), 0, 500).recordsScanned());
                assertEquals(
                    2,
                    backfillTransaction.rebuildCollisionReport(
                        catalog, fixture.workspace().getId()));
                return null;
            });
    }

    @Test
    void unservablePlacementReturns503WithoutDefaultFallback() throws Exception {
        ControlFixture fixture = newControlFixture("silo");
        tenantWorkScope.withCatalog(null, () -> {
            Person first =
                newPerson(fixture.workspace(), "Silo Default One", "silo-one@example.com");
            Person second =
                newPerson(fixture.workspace(), "Silo Default Two", "silo-two@example.com");
            insertDefaultIdentity(first, "silo-secret@example.com");
            insertDefaultIdentity(second, "silo-secret@example.com");
            long firstIdentity = identityId(first, "silo-secret@example.com");
            long secondIdentity = identityId(second, "silo-secret@example.com");
            jdbcTemplate.update(
                """
                INSERT INTO identity_collision (
                  workspace_id, person_identity_id, rebuilt_at
                )
                VALUES (?, ?, ?), (?, ?, ?)
                """,
                fixture.workspace().getId(),
                firstIdentity,
                LocalDateTime.of(2026, 7, 25, 14, 0),
                fixture.workspace().getId(),
                secondIdentity,
                LocalDateTime.of(2026, 7, 25, 14, 0));
            return null;
        });
        insertPlacement(
            fixture.organization().getId(),
            "connex_operated_silo",
            "cnx_silo_" + compactUuid());
        AtomicBoolean invoked = new AtomicBoolean();

        assertThrows(
            ServiceUnavailableException.class,
            () -> tenantWorkScope.withWorkspacePlacement(
                fixture.workspace().getId(),
                (orgId, catalog) -> {
                    invoked.set(true);
                    return null;
                }));
        assertFalse(invoked.get());

        mockMvc.perform(get("/api/identity-collisions")
                .header("X-Workspace-Id", fixture.workspace().getId())
                .session(login(fixture.user().getUsername())))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string("This deployment cannot serve the request"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("silo-secret@example.com"))));
        assertEquals(defaultCatalog(), currentCatalog());
    }

    private ControlFixture newControlFixture(String prefix) {
        String suffix = compactUuid().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Identity " + prefix + " " + suffix);
        organization.setSlug("identity-" + prefix + "-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());

        Workspace workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Identity " + prefix + " " + suffix);
        workspace.setSlug("identity-" + prefix + "-" + suffix);
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());

        User user = new User();
        user.setUsername("identity_route_" + suffix);
        user.setDisplayName("Identity Route " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        workspaceMapper.addMember(workspace.getId(), user.getId(), "member");
        return new ControlFixture(organization, workspace, user);
    }

    private void insertPlacement(int orgId, String mode, String handle) {
        OrgPlacement placement = new OrgPlacement();
        placement.setOrgId(orgId);
        placement.setPlacementMode(mode);
        placement.setDatabaseHandle(handle);
        placement.setStorageEncryptionMode("provider_managed");
        placement.setKeyController("connex_cloud_provider");
        placement.setRevocationSupported(false);
        orgPlacementMapper.insert(placement);
    }

    private void createScratchCatalog(String catalog) {
        String scratch = identifier(catalog);
        String source = identifier(defaultCatalog());
        jdbcTemplate.execute("CREATE DATABASE `" + scratch
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        for (String table : List.of(
                "person",
                "company",
                "person_identity",
                "company_identity",
                "identity_collision")) {
            jdbcTemplate.execute(
                "CREATE TABLE `" + scratch + "`.`" + table
                    + "` LIKE `" + source + "`.`" + table + "`");
        }
    }

    private void seedScratchPerson(
            String catalog,
            int workspaceId,
            int personId,
            String name,
            String email) {
        jdbcTemplate.update(
            "INSERT INTO `" + identifier(catalog)
                + "`.person (id, workspace_id, name, email) VALUES (?, ?, ?, ?)",
            personId,
            workspaceId,
            name,
            email);
    }

    private Person newPerson(Workspace workspace, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setPhone("090-1234-" + String.format("%04d", Math.floorMod(name.hashCode(), 10_000)));
        person.setTitle("Engineer");
        personMapper.insert(person);
        return person;
    }

    private void insertDefaultIdentity(Person person, String normalizedValue) {
        jdbcTemplate.update(
            """
            INSERT INTO person_identity (
              workspace_id, person_id, kind, `value`, normalized_value,
              source_system, acquired_at
            )
            VALUES (?, ?, 'email', ?, ?, 'manual', CURRENT_TIMESTAMP)
            """,
            person.getWorkspaceId(),
            person.getId(),
            person.getEmail(),
            normalizedValue);
    }

    private long identityId(Person person, String normalizedValue) {
        return jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM person_identity
            WHERE workspace_id = ? AND person_id = ? AND normalized_value = ?
            """,
            Long.class,
            person.getWorkspaceId(),
            person.getId(),
            normalizedValue);
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

    private boolean tableExists(String catalog, String table) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = ? AND table_name = ?
            """,
            Integer.class,
            catalog,
            table);
        return count != null && count > 0;
    }

    private String currentCatalog() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT DATABASE()")) {
            resultSet.next();
            return resultSet.getString(1);
        }
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

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ControlFixture(
            Organization organization,
            Workspace workspace,
            User user) {
    }

    private static final class RollbackProbe extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
