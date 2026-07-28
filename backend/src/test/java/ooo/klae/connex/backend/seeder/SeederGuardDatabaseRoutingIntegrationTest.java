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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
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
        SeederStartupConfigurationValidator.JdbcTarget configuredTarget =
            SeederStartupConfigurationValidator.verifiedTarget(
                configuredUrl,
                "CONNEX_DB_URL",
                false
            );
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping seeder routing test"
        );
        bootstrapUrl = mysqlBootstrapUrl(configuredTarget);
        try (Connection connection = DriverManager.getConnection(
                bootstrapUrl,
                username,
                password
            );
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + CONTROL_CATALOG + "`");
            statement.execute("DROP DATABASE IF EXISTS `" + BLOCKED_CATALOG + "`");
            databaseAccessible = true;
        } catch (SQLException ignored) {
            throw new IllegalStateException(
                "Cannot prepare seeder guard scratch catalogs",
                (Throwable) null
            );
        }
    }

    @AfterAll
    static void removeScratchCatalogs() {
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
        } catch (SQLException ignored) {
            throw new IllegalStateException(
                "Cannot remove seeder guard scratch catalogs",
                (Throwable) null
            );
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
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "true")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.tenancy.routing.mode", "single-database")
            .withProperty("connex.object-storage.legacy-migration.mode", "off")
            .withProperty("spring.flyway.placeholder-replacement", "false");
        environment.getPropertySources().addLast(new MapPropertySource(
            "Config resource 'class path resource [application-seeder.yml]'",
            Map.of(
                "spring.flyway.locations",
                "classpath:db/migration",
                "spring.flyway.callback-locations",
                List.of(),
                "spring.sql.init.mode",
                "never",
                "spring.sql.init.data-locations",
                SeederStartupConfigurationValidator.PROJECT_SQL_INIT_DATA_LOCATION,
                "mybatis.mapper-locations",
                SeederStartupConfigurationValidator.PROJECT_MYBATIS_MAPPER_LOCATIONS,
                "mybatis.type-aliases-package",
                SeederStartupConfigurationValidator.PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE,
                "mybatis.configuration.map-underscore-to-camel-case",
                true
            )
        ));
        environment.setActiveProfiles("seeder");
        return environment;
    }

    private static String mysqlBootstrapUrl(
            SeederStartupConfigurationValidator.JdbcTarget configuredTarget) {
        String authorityHost = configuredTarget.host().contains(":")
            ? "[" + configuredTarget.host() + "]"
            : configuredTarget.host();
        return "jdbc:mysql://" + authorityHost + ":" + configuredTarget.port()
            + "/mysql?allowPublicKeyRetrieval=true&sslMode=DISABLED";
    }
}
