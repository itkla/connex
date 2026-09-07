package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicApiCredentialRollbackSafetyIntegrationTest {

    private static final String SCRATCH_CATALOG =
        "connex_public_api_v202_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createV202Catalog() throws SQLException {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping V202 rollback test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(false,
                "Cannot create V202 rollback scratch catalog: " + exception.getMessage());
        }
        Flyway flyway = Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration")
            .callbacks(new AuditLogV126MigrationCallback())
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .target(MigrationVersion.fromVersion("202"))
            .load();
        flyway.migrate();
        assertEquals(MigrationVersion.fromVersion("202"), flyway.info().current().getVersion());
    }

    @AfterAll
    static void dropScratchCatalog() throws SQLException {
        if (!created) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + SCRATCH_CATALOG + "`");
        }
    }

    @Test
    void oldBinaryDeletesARevokerAccountAndCredentialKeepsRevocation() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            Fixture fixture = seedFixture(statement, 9562100);
            insertCredential(statement, fixture, 9562101, fixture.revokerId(), true);

            assertEquals(1, statement.executeUpdate(
                "DELETE FROM app_user WHERE id = " + fixture.revokerId()));
            assertEquals(1, scalar(statement,
                "SELECT COUNT(*) FROM api_credential"
                    + " WHERE id = 9562101 AND revoked_at IS NOT NULL AND revoked_by_id IS NULL"));
            assertEquals(1, scalar(statement,
                "SELECT COUNT(*) FROM api_credential_scope WHERE credential_id = 9562101"));
        }
    }

    @Test
    void oldBinaryCanDeleteEveryV202ParentRow() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            Fixture membershipFixture = seedFixture(statement, 9562200);
            insertCredential(statement, membershipFixture, 9562201, null, false);
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM workspace_member WHERE workspace_id = "
                    + membershipFixture.workspaceId() + " AND user_id = "
                    + membershipFixture.creatorId()));
            assertEquals(0, scalar(statement,
                "SELECT COUNT(*) FROM api_credential WHERE id = 9562201"));

            Fixture creatorFixture = seedFixture(statement, 9562300);
            insertCredential(statement, creatorFixture, 9562301, null, false);
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM app_user WHERE id = " + creatorFixture.creatorId()));
            assertEquals(0, scalar(statement,
                "SELECT COUNT(*) FROM api_credential WHERE id = 9562301"));

            Fixture workspaceFixture = seedFixture(statement, 9562400);
            insertCredential(statement, workspaceFixture, 9562401, null, false);
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM workspace WHERE id = " + workspaceFixture.workspaceId()));
            assertEquals(0, scalar(statement,
                "SELECT COUNT(*) FROM api_credential WHERE id = 9562401"));
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM organization WHERE id = " + workspaceFixture.organizationId()));

            Fixture legacyInsertFixture = seedFixture(statement, 9562500);
            int legacyUserId = (int) scalar(statement, "SELECT COALESCE(MAX(id), 0) + 1 FROM app_user");
            insertUser(statement, legacyUserId, "legacy-" + legacyUserId);
            assertEquals(1, statement.executeUpdate(
                "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES ("
                    + legacyInsertFixture.workspaceId() + ", " + legacyUserId + ", 'member')"));
            assertEquals(1, scalar(statement,
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = "
                    + legacyInsertFixture.workspaceId() + " AND user_id = " + legacyUserId
                    + " AND membership_id IS NOT NULL"));

            assertDeleteRule(statement, "fk_api_credential_workspace", "CASCADE");
            assertDeleteRule(statement, "fk_api_credential_organization", "CASCADE");
            assertDeleteRule(statement, "fk_api_credential_creator", "CASCADE");
            assertDeleteRule(statement, "fk_api_credential_membership", "CASCADE");
            assertDeleteRule(statement, "fk_api_credential_revoker", "SET NULL");
            assertDeleteRule(statement, "fk_api_credential_scope_credential", "CASCADE");
        }
    }

    private static Fixture seedFixture(Statement statement, int base) throws SQLException {
        int organizationId = base;
        int workspaceId = base + 1;
        int creatorId = base + 2;
        int revokerId = base + 3;
        statement.executeUpdate("INSERT INTO organization (id, name, slug) VALUES ("
            + organizationId + ", 'Rollback organization', 'rollback-org-" + base + "')");
        statement.executeUpdate("INSERT INTO workspace (id, org_id, name, slug) VALUES ("
            + workspaceId + ", " + organizationId
            + ", 'Rollback workspace', 'rollback-workspace-" + base + "')");
        insertUser(statement, creatorId, "creator-" + base);
        insertUser(statement, revokerId, "revoker-" + base);
        statement.executeUpdate(
            "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES ("
                + workspaceId + ", " + creatorId + ", 'member')");
        statement.executeUpdate(
            "INSERT INTO workspace_member (workspace_id, user_id, role) VALUES ("
                + workspaceId + ", " + revokerId + ", 'member')");
        long membershipId = scalar(statement,
            "SELECT membership_id FROM workspace_member WHERE workspace_id = "
                + workspaceId + " AND user_id = " + creatorId);
        return new Fixture(organizationId, workspaceId, creatorId, revokerId, membershipId);
    }

    private static void insertUser(Statement statement, int id, String identity)
            throws SQLException {
        statement.executeUpdate("INSERT INTO app_user"
            + " (id, username, display_name, email, password_hash, timezone) VALUES ("
            + id + ", '" + identity + "', 'Rollback user', '" + identity
            + "@example.test', 'hash', 'UTC')");
    }

    private static void insertCredential(
            Statement statement,
            Fixture fixture,
            long credentialId,
            Integer revokerId,
            boolean revoked) throws SQLException {
        String tokenHash = String.format("%064d", credentialId);
        String revokedAt = revoked ? "UTC_TIMESTAMP(6)" : "NULL";
        String revokedBy = revokerId == null ? "NULL" : revokerId.toString();
        statement.executeUpdate("INSERT INTO api_credential"
            + " (id, workspace_id, organization_id, created_by_id, membership_id, name,"
            + " token_hash, token_last4, expires_at, revoked_at, revoked_by_id) VALUES ("
            + credentialId + ", " + fixture.workspaceId() + ", "
            + fixture.organizationId() + ", " + fixture.creatorId() + ", "
            + fixture.membershipId() + ", 'Rollback credential', '" + tokenHash
            + "', 'v202', UTC_TIMESTAMP(6) + INTERVAL 1 DAY, " + revokedAt + ", "
            + revokedBy + ")");
        statement.executeUpdate("INSERT INTO api_credential_scope (credential_id, scope) VALUES ("
            + credentialId + ", 'crm.read')");
    }

    private static void assertDeleteRule(
            Statement statement, String constraintName, String expected) throws SQLException {
        String rule = stringScalar(statement,
            "SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = '"
                + constraintName + "'");
        assertEquals(expected, rule, constraintName);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(scratchUrl, username, password);
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertNotNull(resultSet);
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String stringScalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertNotNull(resultSet);
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static String withCatalog(String configuredUrl, String catalog) {
        int queryIndex = configuredUrl.indexOf('?');
        String query = queryIndex >= 0 ? configuredUrl.substring(queryIndex) : "";
        String base = queryIndex >= 0 ? configuredUrl.substring(0, queryIndex) : configuredUrl;
        int slashIndex = base.lastIndexOf('/');
        return base.substring(0, slashIndex + 1) + catalog + query;
    }

    private record Fixture(
            int organizationId,
            int workspaceId,
            int creatorId,
            int revokerId,
            long membershipId) {
    }
}
