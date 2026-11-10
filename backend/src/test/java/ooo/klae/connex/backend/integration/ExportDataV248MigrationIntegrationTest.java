package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies the one-time compatibility grant against a minimal pre-V248 control schema. */
class ExportDataV248MigrationIntegrationTest {
    private static final String RESOURCE = "db/migration/control/V248__export_data_permission.sql";
    private static String configuredUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void requireDatabase() {
        configuredUrl = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(configuredUrl != null && username != null && password != null,
            "Database credentials required");
    }

    @Test
    void existingBuiltInAndCustomRolesKeepExportAndNewCustomRolesStartWithoutIt() throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            Flyway flyway = migration(catalog);

            assertEquals(1, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                assertLegacyGrants(connection);
                statement.executeUpdate("INSERT INTO workspace_role (id, workspace_id, name) VALUES"
                    + " (31, 11, 'New role'), (32, 22, 'Another new role')");
                assertEquals(List.of("0"), rows(connection,
                    "SELECT COUNT(*) FROM workspace_role_permission"
                        + " WHERE workspace_role_id IN (31, 32) AND permission = 'EXPORT_DATA'"));
                statement.executeUpdate("UPDATE workspace SET export_data_member = FALSE WHERE id = 11");
                statement.executeUpdate("UPDATE organization SET export_data_admin = FALSE WHERE id = 1");
            }

            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(catalog)) {
                assertEquals(List.of("0"), rows(connection,
                    "SELECT COUNT(*) FROM workspace_role_permission"
                        + " WHERE workspace_role_id IN (31, 32) AND permission = 'EXPORT_DATA'"));
                assertEquals(List.of("0"), rows(connection,
                    "SELECT export_data_member FROM workspace WHERE id = 11"));
                assertEquals(List.of("0"), rows(connection,
                    "SELECT export_data_admin FROM organization WHERE id = 1"));
            }
        }
    }

    @Test
    void repeatingMigrationSqlDoesNotDuplicateGrantsOrResetWithdrawnBuiltInPermissions() throws Exception {
        try (MySqlScratchCatalog catalog = catalog()) {
            createLegacyTables(catalog);
            try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
                for (String sql : migrationStatements()) {
                    statement.execute(sql);
                }
                assertLegacyGrants(connection);
                statement.executeUpdate("UPDATE workspace SET export_data_member = FALSE WHERE id = 11");
                statement.executeUpdate("UPDATE organization SET export_data_admin = FALSE WHERE id = 1");
                for (String sql : migrationStatements()) {
                    statement.execute(sql);
                }
                assertEquals(List.of("4"), rows(connection,
                    "SELECT COUNT(*) FROM workspace_role_permission WHERE permission = 'EXPORT_DATA'"));
                assertEquals(List.of("0"), rows(connection,
                    "SELECT export_data_member FROM workspace WHERE id = 11"));
                assertEquals(List.of("0"), rows(connection,
                    "SELECT export_data_admin FROM organization WHERE id = 1"));
                assertEquals(List.of("COMPANY_CREATE", "EXPORT_DATA"), rows(connection,
                    "SELECT permission FROM workspace_role_permission"
                        + " WHERE workspace_role_id = 12 ORDER BY permission"));
            }
        }
    }

    private static void createLegacyTables(MySqlScratchCatalog catalog) throws SQLException {
        try (Connection connection = connection(catalog); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE organization (id INT PRIMARY KEY, name VARCHAR(64) NOT NULL)");
            statement.execute("CREATE TABLE workspace (id INT PRIMARY KEY, org_id INT NOT NULL,"
                + " name VARCHAR(64) NOT NULL, FOREIGN KEY (org_id) REFERENCES organization(id))");
            statement.execute("CREATE TABLE workspace_role (id INT PRIMARY KEY, workspace_id INT NOT NULL,"
                + " name VARCHAR(64) NOT NULL, FOREIGN KEY (workspace_id) REFERENCES workspace(id))");
            statement.execute("CREATE TABLE workspace_role_permission (workspace_role_id INT NOT NULL,"
                + " permission VARCHAR(48) NOT NULL, PRIMARY KEY (workspace_role_id, permission),"
                + " FOREIGN KEY (workspace_role_id) REFERENCES workspace_role(id))");
            statement.executeUpdate("INSERT INTO organization (id, name) VALUES (1, 'One'), (2, 'Two')");
            statement.executeUpdate("INSERT INTO workspace (id, org_id, name) VALUES"
                + " (11, 1, 'One workspace'), (22, 2, 'Two workspace')");
            statement.executeUpdate("INSERT INTO workspace_role (id, workspace_id, name) VALUES"
                + " (11, 11, 'Empty one'), (12, 11, 'Existing one'),"
                + " (21, 22, 'Empty two'), (22, 22, 'Existing two')");
            statement.executeUpdate("INSERT INTO workspace_role_permission (workspace_role_id, permission) VALUES"
                + " (12, 'COMPANY_CREATE'), (22, 'REPORT_READ')");
        }
    }

    private static void assertLegacyGrants(Connection connection) throws SQLException {
        assertEquals(List.of("11|1|1|1", "22|1|1|1"), rows(connection,
            "SELECT CONCAT(id, '|', export_data_owner, '|', export_data_admin, '|', export_data_member)"
                + " FROM workspace ORDER BY id"));
        assertEquals(List.of("1|1|1", "2|1|1"), rows(connection,
            "SELECT CONCAT(id, '|', export_data_owner, '|', export_data_admin) FROM organization ORDER BY id"));
        assertEquals(List.of("11", "12", "21", "22"), rows(connection,
            "SELECT workspace_role_id FROM workspace_role_permission"
                + " WHERE permission = 'EXPORT_DATA' ORDER BY workspace_role_id"));
        assertEquals(List.of("12|COMPANY_CREATE", "22|REPORT_READ"), rows(connection,
            "SELECT CONCAT(workspace_role_id, '|', permission) FROM workspace_role_permission"
                + " WHERE permission <> 'EXPORT_DATA' ORDER BY workspace_role_id, permission"));
    }

    private static List<String> migrationStatements() throws IOException {
        String migration;
        try (var input = ExportDataV248MigrationIntegrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration " + RESOURCE);
            }
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String line : migration.lines().toList()) {
            pending.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String sql = pending.toString().stripTrailing();
                statements.add(sql.substring(0, sql.length() - 1));
                pending.setLength(0);
            }
        }
        assertTrue(pending.toString().replaceAll("(?m)--[^\\r\\n]*", "").isBlank());
        assertTrue(!statements.isEmpty());
        return statements;
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(result.getString(1));
            }
            return rows;
        }
    }

    private static Connection connection(MySqlScratchCatalog catalog) throws SQLException {
        return DriverManager.getConnection(catalog.url(), username, password);
    }

    private static MySqlScratchCatalog catalog() throws SQLException {
        return MySqlScratchCatalog.create(Objects.requireNonNull(configuredUrl), username, password,
            "export_data_v248_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    private static Flyway migration(MySqlScratchCatalog catalog) {
        return Flyway.configure().dataSource(catalog.url(), username, password)
            .locations("classpath:db/migration/control").baselineOnMigrate(true).baselineVersion("247")
            .target("248").load();
    }
}
