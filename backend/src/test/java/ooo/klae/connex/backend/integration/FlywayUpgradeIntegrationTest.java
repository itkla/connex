package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Migrates representative populated V73 data through the current Flyway lineage on real MySQL.
 */
class FlywayUpgradeIntegrationTest {
    private static final String SCRATCH_CATALOG =
        "connex_upgrade_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String bootstrapUrl;
    private static String scratchUrl;
    private static String username;
    private static String password;
    private static boolean created;

    @BeforeAll
    static void createScratchCatalog() {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping Flyway upgrade integration test");
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        scratchUrl = withCatalog(configuredUrl, SCRATCH_CATALOG);
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCRATCH_CATALOG + "` CHARACTER SET utf8mb4");
            created = true;
        } catch (SQLException exception) {
            assumeTrue(
                false,
                "Cannot create Flyway upgrade scratch catalog: " + exception.getMessage());
        }
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
    void migratesPopulatedV73MediaAndImportStateToLatest() throws SQLException {
        migrateTo("73");
        seedV73Data();
        migrateTo("81");
        seedV81Data();
        migrateTo("84");
        seedV84Reservation();
        migrateTo("110");
        seedV110SavedView();

        Flyway latest = flyway(null);
        latest.migrate();

        assertEquals(0, latest.info().pending().length);
        assertNotNull(latest.info().current());
        assertTrue(latest.info().current().getVersion().compareTo(MigrationVersion.fromVersion("111")) >= 0);
        try (Connection connection = connection()) {
            JsonNode migratedConfig = JsonMapper.builder().build().readTree(
                stringScalar(connection, "SELECT config_json FROM saved_view WHERE id = 9101"));
            JsonNode expectedConfig = JsonMapper.builder().build().readTree("""
                {
                  "query":"legacy",
                  "filters":{"owner":["me"]},
                  "unknown":{"nested":[1,true]},
                  "version":1
                }
                """);
            assertEquals(expectedConfig, migratedConfig);
            assertEquals("2025-06-15 12:34:56", stringScalar(connection, """
                SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s')
                FROM saved_view
                WHERE id = 9101
                """));
            assertEquals(6, scalar(connection, """
                SELECT COUNT(*)
                FROM (
                    SELECT url FROM attachment WHERE workspace_id = 9101
                    UNION ALL SELECT image_url FROM person WHERE workspace_id = 9101
                    UNION ALL SELECT logo_url FROM company WHERE workspace_id = 9101
                ) references_after_upgrade
                WHERE url IS NOT NULL
                """));
            assertEquals(3, scalar(connection, """
                SELECT COUNT(*)
                FROM (
                    SELECT url FROM attachment WHERE workspace_id = 9101
                    UNION ALL SELECT image_url FROM person WHERE workspace_id = 9101
                    UNION ALL SELECT logo_url FROM company WHERE workspace_id = 9101
                ) references_after_upgrade
                WHERE url LIKE '/attachments/%'
                   OR url LIKE '/contact-pictures/%'
                   OR url LIKE '/company-logos/%'
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM app_user
                WHERE id = 9101
                  AND locale = 'ja'
                  AND profile_picture_url = '/profile-pictures/user-9101-legacy.jpg'
                """));
            assertEquals(3, scalar(connection,
                "SELECT COUNT(*) FROM managed_object_usage WHERE workspace_id = 9101"));
            assertEquals(52_430_034L, scalar(connection,
                "SELECT used_bytes FROM object_storage_quota WHERE workspace_id = 9101"));
            assertEquals(3, scalar(connection,
                "SELECT object_count FROM object_storage_quota WHERE workspace_id = 9101"));
            assertEquals(1, scalar(connection,
                "SELECT delete_passes_remaining FROM object_deletion_queue WHERE workspace_id = 9101"));
            assertEquals(1, scalar(connection,
                "SELECT delete_passes_remaining FROM user_object_deletion_queue WHERE object_key = 'users/9101/profile/old.jpg'"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM business_card_import_request
                WHERE workspace_id = 9101
                  AND created_by_user_id IS NULL
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM business_card_import_request
                WHERE workspace_id = 9101
                  AND idempotency_key = '11111111-1111-4111-8111-111111111111'
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM business_card_import_request
                WHERE workspace_id = 9101
                  AND idempotency_key = '22222222-2222-4222-8222-222222222222'
                """));
            assertEquals(1, indexCount(connection, "company", "idx_company_workspace_name"));
            assertEquals(1, indexCount(
                connection,
                "business_card_import_request",
                "idx_business_card_import_request_workspace_expiry"));
            assertEquals(0, scalar(connection, """
                SELECT IS_NULLABLE = 'YES'
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'business_card_import_request'
                  AND COLUMN_NAME = 'created_by_user_id'
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'object_storage_backend_identity'
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO business_card_import_request (
                    workspace_id, idempotency_key, created_by_user_id, request_fingerprint,
                    expires_at, submission_expires_at, reservation_slot)
                VALUES (
                    9101, '33333333-3333-4333-8333-333333333333', NULL, NULL,
                    DATE_ADD(NOW(6), INTERVAL 1 DAY), DATE_ADD(NOW(6), INTERVAL 2 MINUTE), 1)
                """));
        }
    }

    private static void seedV73Data() throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, """
                INSERT INTO app_user (
                    id, username, display_name, email, password_hash, timezone, locale,
                    profile_picture_url)
                VALUES (
                    9101, 'upgrade-user', 'Upgrade User', 'upgrade@example.test', NULL,
                    'Asia/Tokyo', 'ja', '/profile-pictures/user-9101-legacy.jpg')
                """);
            execute(connection,
                "INSERT INTO workspace (id, org_id, name, slug) VALUES (9101, 1, 'Upgrade Workspace', 'upgrade-workspace')");
            execute(connection, """
                INSERT INTO company (id, workspace_id, name, logo_url) VALUES
                    (9101, 9101, 'Legacy Company', '/company-logos/company-9101-legacy.png'),
                    (9102, 9101, 'Managed Company', '/api/companies/9102/logo/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.png')
                """);
            execute(connection, """
                INSERT INTO person (id, workspace_id, name, image_url) VALUES
                    (9101, 9101, 'Legacy Person', '/contact-pictures/contact-9101-legacy.jpg'),
                    (9102, 9101, 'Managed Person', '/api/persons/9102/profile-picture/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.jpg')
                """);
            execute(connection, """
                INSERT INTO attachment (
                    id, workspace_id, entity_type, entity_id, file_name, url, content_type, size)
                VALUES
                    (9101, 9101, 'person', 9101, 'legacy.pdf', '/attachments/person/person-9101-legacy.pdf', 'application/pdf', 10),
                    (9102, 9101, 'person', 9102, 'managed.pdf', '/api/attachments/content/cccccccc-cccc-4ccc-8ccc-cccccccccccc.pdf', 'application/pdf', 1234)
                """);
        }
    }

    private static void seedV81Data() throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, """
                INSERT INTO object_deletion_queue (
                    workspace_id, object_key, attempts, next_attempt_at)
                VALUES (9101, 'workspaces/9101/attachments/old.pdf', 1, NOW(6))
                """);
            execute(connection, """
                INSERT INTO user_object_deletion_queue (object_key, attempts, next_attempt_at)
                VALUES ('users/9101/profile/old.jpg', 1, NOW(6))
                """);
            execute(connection, """
                INSERT INTO business_card_import_request (
                    workspace_id, idempotency_key, request_fingerprint, person_id,
                    attachment_id, company_id, created_at, completed_at)
                VALUES (
                    9101, '11111111-1111-4111-8111-111111111111', UNHEX(REPEAT('ab', 32)),
                    9102, 9102, 9102, '2026-01-01 00:00:00', '2026-01-01 00:01:00')
                """);
        }
    }

    private static void seedV84Reservation() throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, """
                INSERT INTO business_card_import_request (
                    workspace_id, idempotency_key, request_fingerprint, created_at, expires_at)
                VALUES (
                    9101, '22222222-2222-4222-8222-222222222222', NULL,
                    '2026-01-02 00:00:00', '2026-01-03 00:00:00')
                """);
        }
    }

    private static void seedV110SavedView() throws SQLException {
        try (Connection connection = connection()) {
            execute(connection, """
                INSERT INTO saved_view (
                    id, workspace_id, user_id, record_type, name, config_json, position,
                    created_at, updated_at)
                VALUES (
                    9101, 9101, 9101, 'company', 'Legacy saved view',
                    '{"query":"legacy","filters":{"owner":["me"]},"unknown":{"nested":[1,true]}}',
                    4, '2025-06-15 12:00:00', '2025-06-15 12:34:56')
                """);
        }
    }

    private static Flyway migrateTo(String version) {
        Flyway flyway = flyway(version);
        flyway.migrate();
        return flyway;
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(scratchUrl, username, password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"));
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(scratchUrl, username, password);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String stringScalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static long indexCount(Connection connection, String table, String index) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = ? AND table_name = ? AND index_name = ?
                """)) {
            statement.setString(1, SCRATCH_CATALOG);
            statement.setString(2, table);
            statement.setString(3, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static String withCatalog(String jdbcUrl, String catalog) {
        int authorityEnd = jdbcUrl.indexOf('/', "jdbc:mysql://".length());
        if (authorityEnd < 0) {
            throw new IllegalArgumentException("CONNEX_DB_URL must include a database path");
        }
        int queryStart = jdbcUrl.indexOf('?', authorityEnd);
        String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return jdbcUrl.substring(0, authorityEnd + 1) + catalog + suffix;
    }
}
