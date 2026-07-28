package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.DeploymentProperties;

/**
 * Proves driver target selectors are refused before Connector/J can create their catalog.
 */
class SeederGuardDatabaseRoutingIntegrationTest {

    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "");
    private static final String CONTROL_CATALOG = "cnx_guard_control_" + RUN_ID;
    private static final String BLOCKED_CATALOG = "cnx_guard_blocked_" + RUN_ID;

    private static String bootstrapUrl;
    private static String username;
    private static String password;
    private static boolean databaseAccessible;

    @BeforeAll
    static void prepareDatabase() {
        String configuredUrl = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED"
        );
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping seeder routing test"
        );
        bootstrapUrl = withCatalog(configuredUrl, "mysql");
        try (Connection connection = DriverManager.getConnection(
                bootstrapUrl,
                username,
                password
            );
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + CONTROL_CATALOG + "`");
            statement.execute("DROP DATABASE IF EXISTS `" + BLOCKED_CATALOG + "`");
            databaseAccessible = true;
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot prepare seeder guard scratch catalogs",
                exception
            );
        }
    }

    @AfterAll
    static void removeScratchCatalogs() throws SQLException {
        if (!databaseAccessible) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                bootstrapUrl,
                username,
                password
            );
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + CONTROL_CATALOG + "`");
            statement.execute("DROP DATABASE IF EXISTS `" + BLOCKED_CATALOG + "`");
        }
    }

    @Test
    void refusesDbnameBeforeCreateDatabaseIfNotExistCanCreateItsCatalog() throws SQLException {
        Properties controlProperties = new Properties();
        controlProperties.setProperty("user", username);
        controlProperties.setProperty("password", password);
        controlProperties.setProperty("dbname", CONTROL_CATALOG);
        controlProperties.setProperty("createDatabaseIfNotExist", "true");
        try (Connection ignored = DriverManager.getConnection(bootstrapUrl, controlProperties)) {
            assertTrue(catalogExists(CONTROL_CATALOG));
        }

        try (HikariDataSource guardedDataSource = new HikariDataSource()) {
            guardedDataSource.setJdbcUrl(bootstrapUrl);
            guardedDataSource.setUsername(username);
            guardedDataSource.setPassword(password);
            guardedDataSource.addDataSourceProperty("dbname", BLOCKED_CATALOG);
            guardedDataSource.addDataSourceProperty("createDatabaseIfNotExist", "true");
            MockEnvironment environment = seederEnvironment()
                .withProperty("spring.datasource.url", bootstrapUrl)
                .withProperty(
                    "spring.datasource.hikari.data-source-properties.dbname",
                    BLOCKED_CATALOG
                )
                .withProperty(
                    "spring.datasource.hikari.data-source-properties.createDatabaseIfNotExist",
                    "true"
                );
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                guardedDataSource
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(
                exception.getMessage().contains(
                    "spring.datasource.hikari.data-source-properties"
                )
            );
            assertFalse(catalogExists(BLOCKED_CATALOG));
        }
    }

    private static boolean catalogExists(String catalog) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                bootstrapUrl,
                username,
                password
            );
                PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM information_schema.SCHEMATA
                    WHERE SCHEMA_NAME = ?
                    """)) {
            statement.setString(1, catalog);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static MockEnvironment seederEnvironment() {
        return new MockEnvironment()
            .withProperty("spring.profiles.active", "seeder")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.tenancy.routing.mode", "single-database");
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
