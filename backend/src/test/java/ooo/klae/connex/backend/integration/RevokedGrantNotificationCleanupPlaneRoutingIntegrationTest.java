package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RevokedGrantNotificationCleanup;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Proves under placement routing that the email change's revoked-grant cleanup deletes each
 * revoked workspace's notifications and baselines in that workspace's own catalog, across the
 * default catalog and two dedicated ones, and keeps them where the account holds a membership
 * again (#1708).
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RevokedGrantNotificationCleanupPlaneRoutingIntegrationTest {

    @Autowired private RevokedGrantNotificationCleanup cleanup;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgPlacementMapper orgPlacementMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantWorkScope tenantWorkScope;

    private final List<Integer> workspaceIds = new ArrayList<>();
    private final List<Integer> organizationIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final List<String> scratchCatalogs = new ArrayList<>();

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "connex.tenancy.routing.mode",
            () -> TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        registry.add(
            "connex.tenancy.routing.default-catalog",
            RevokedGrantNotificationCleanupPlaneRoutingIntegrationTest::defaultCatalog);
    }

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        tenantWorkScope.withCatalog(null, () -> {
            for (int workspaceId : workspaceIds) {
                jdbcTemplate.update("DELETE FROM notification WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update(
                    "DELETE FROM historical_notification_baseline WHERE workspace_id = ?", workspaceId);
                jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
            }
            for (int organizationId : organizationIds) {
                jdbcTemplate.update("DELETE FROM org_placement WHERE org_id = ?", organizationId);
                jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organizationId);
            }
            for (int userId : userIds) {
                jdbcTemplate.update(
                    "DELETE FROM notification_recipient_state WHERE recipient_id = ?", userId);
                jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
            }
            return null;
        });
        for (String scratchCatalog : scratchCatalogs) {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS `" + identifier(scratchCatalog) + "`");
        }
    }

    @Test
    void eachRevokedWorkspaceIsCleanedInItsOwnCatalogAndAReinvitationKeepsItsRows() {
        Workspace shared = newWorkspace(newOrganization());
        Organization firstOrganization = newOrganization();
        Workspace first = newWorkspace(firstOrganization);
        String firstCatalog = dedicatedCatalog(firstOrganization, "cnx_revoked_a_");
        Organization secondOrganization = newOrganization();
        Workspace second = newWorkspace(secondOrganization);
        String secondCatalog = dedicatedCatalog(secondOrganization, "cnx_revoked_b_");
        Organization reinvitedOrganization = newOrganization();
        Workspace reinvited = newWorkspace(reinvitedOrganization);
        String reinvitedCatalog = dedicatedCatalog(reinvitedOrganization, "cnx_revoked_c_");
        User user = newUser();
        seed(defaultCatalog(), shared.getId(), user.getId());
        seed(firstCatalog, first.getId(), user.getId());
        seed(secondCatalog, second.getId(), user.getId());
        seed(reinvitedCatalog, reinvited.getId(), user.getId());
        workspaceMapper.addPendingMember(reinvited.getId(), user.getId(), "member");

        cleanup.cleanUp(user.getId(), List.of(
            revoked(second), revoked(shared), revoked(reinvited), revoked(first)));

        assertEquals(0, rows(defaultCatalog(), "notification", shared.getId(), user.getId()));
        assertEquals(0, rows(defaultCatalog(), "historical_notification_baseline", shared.getId(), user.getId()));
        assertEquals(0, rows(firstCatalog, "notification", first.getId(), user.getId()));
        assertEquals(0, rows(firstCatalog, "historical_notification_baseline", first.getId(), user.getId()));
        assertEquals(0, rows(secondCatalog, "notification", second.getId(), user.getId()));
        assertEquals(0, rows(secondCatalog, "historical_notification_baseline", second.getId(), user.getId()));
        assertEquals(1, rows(reinvitedCatalog, "notification", reinvited.getId(), user.getId()));
        assertEquals(1, rows(
            reinvitedCatalog, "historical_notification_baseline", reinvited.getId(), user.getId()));
    }

    private static RevokedInvitationDto revoked(Workspace workspace) {
        return new RevokedInvitationDto(workspace.getId(), workspace.getOrgId(), workspace.getName());
    }

    private void seed(String catalog, int workspaceId, int recipientId) {
        String qualified = "`" + identifier(catalog) + "`.";
        jdbcTemplate.update(
            "INSERT INTO " + qualified + "notification"
                + " (workspace_id, recipient_id, type, category, severity, template_version,"
                + " title, dedupe_key, triggered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())",
            workspaceId, recipientId, "workspace.join", "workspace", "info", 1,
            "Workspace invitation", "workspace.join:" + workspaceId);
        jdbcTemplate.update(
            "INSERT INTO " + qualified + "historical_notification_baseline"
                + " (workspace_id, recipient_id, dedupe_key, notification_type, baseline_severity,"
                + " source_state_hash, import_run_id) VALUES (?, ?, ?, ?, ?, UNHEX(SHA2(?, 256)),"
                + " UNHEX(SHA2(?, 256)))",
            workspaceId, recipientId, "baseline:" + workspaceId, "workspace.join", "info",
            "state:" + workspaceId, "run:" + workspaceId);
    }

    private int rows(String catalog, String table, int workspaceId, int recipientId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `" + identifier(catalog) + "`.`" + identifier(table)
                + "` WHERE workspace_id = ? AND recipient_id = ?",
            Integer.class, workspaceId, recipientId);
        return count == null ? 0 : count;
    }

    private String dedicatedCatalog(Organization organization, String prefix) {
        String scratch = identifier(prefix + compactUuid());
        String source = identifier(defaultCatalog());
        scratchCatalogs.add(scratch);
        jdbcTemplate.execute("CREATE DATABASE `" + scratch
            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        for (String table : TablePlaneRegistry.ORG_DATA_TABLES.stream().sorted().toList()) {
            jdbcTemplate.execute(
                "CREATE TABLE `" + scratch + "`.`" + identifier(table)
                    + "` LIKE `" + source + "`.`" + identifier(table) + "`");
        }
        OrgPlacement placement = OrgPlacement.sharedDefault(organization.getId());
        placement.setPlacementMode("dedicated_database");
        placement.setDatabaseHandle(scratch);
        orgPlacementMapper.insert(placement);
        return scratch;
    }

    private Organization newOrganization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Organization organization = new Organization();
        organization.setName("Revoked Org " + suffix);
        organization.setSlug("revoked-org-" + suffix);
        organizationMapper.insert(organization);
        organizationIds.add(organization.getId());
        return organization;
    }

    private Workspace newWorkspace(Organization organization) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = new Workspace();
        workspace.setName("Revoked Workspace " + suffix);
        workspace.setSlug("revoked-ws-" + suffix);
        workspace.setOrgId(organization.getId());
        workspaceMapper.insert(workspace);
        workspaceIds.add(workspace.getId());
        return workspace;
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("revoked_" + suffix);
        user.setDisplayName("Revoked User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPasswordHash("unused");
        user.setTimezone("UTC");
        userMapper.insert(user);
        userIds.add(user.getId());
        return user;
    }

    private static String defaultCatalog() {
        String catalog = TenantRoutingConfig.databaseFromJdbcUrl(System.getenv("CONNEX_DB_URL"));
        if (catalog != null) {
            return catalog;
        }
        String configured = System.getenv("CONNEX_DB_NAME");
        return configured != null ? configured : "connexdb";
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid test catalog identifier");
        }
        return value;
    }
}
