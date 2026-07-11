package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Adversarial proof that catalog routing cannot leak across checkouts on a real
 * MySQL pool (#440 increment 2). Uses a single-connection Hikari pool so every
 * test provably reuses the same physical connection, wired through the exact
 * production decoration path ({@link TenantRoutingConfig#decorate}). Requires
 * the test DB user to be able to create the scratch catalog
 * {@code connexdb_routing_it}; CI runs as root, and a local run needs the
 * one-time grant documented in {@code backend/AGENTS.md}.
 */
class TenantCatalogRoutingIntegrationTest {

    private static final String SCRATCH_CATALOG = "connexdb_routing_it";

    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static HikariDataSource pool;
    private static TenantRoutingDataSource routing;
    private static final TenantContext TENANT_CONTEXT = new TenantContext();

    @BeforeAll
    static void setUpPool() {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping catalog routing integration test");
        defaultCatalog = catalogFromUrl(url);

        try (Connection bootstrap = DriverManager.getConnection(url, username, password);
                Statement statement = bootstrap.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCRATCH_CATALOG);
        } catch (SQLException e) {
            assumeTrue(false, "Cannot create scratch catalog " + SCRATCH_CATALOG + " (" + e.getMessage()
                + "); grant once as root: GRANT ALL PRIVILEGES ON `connexdb\\_routing\\_%`.* TO '"
                + username + "'@'%';");
        }

        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(1);
        hikari.setPoolName("catalog-routing-it");

        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        routing = TenantRoutingConfig.decorate(hikari, properties, TENANT_CONTEXT);
        pool = hikari;
    }

    @AfterAll
    static void tearDownPool() throws SQLException {
        if (pool == null) {
            return;
        }
        pool.close();
        try (Connection bootstrap = DriverManager.getConnection(url, username, password);
                Statement statement = bootstrap.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + SCRATCH_CATALOG);
        }
    }

    @AfterEach
    void clearContext() {
        TENANT_CONTEXT.clear();
    }

    @Test
    void routedCheckoutSwitchesAndReturnResetsTheSamePhysicalConnection() throws SQLException {
        TENANT_CONTEXT.set(1, 1, 1, "owner", SCRATCH_CATALOG);
        long routedConnectionId;
        try (Connection connection = routing.getConnection()) {
            routedConnectionId = connectionId(connection);
            assertEquals(SCRATCH_CATALOG, currentDatabase(connection));
        }

        TENANT_CONTEXT.clear();
        try (Connection connection = routing.getConnection()) {
            assertEquals(routedConnectionId, connectionId(connection),
                "pool must hand back the same physical connection for this proof to mean anything");
            assertEquals(defaultCatalog, currentDatabase(connection));
        }
    }

    @Test
    void exceptionWhileCheckedOutStillResetsOnReturn() throws SQLException {
        TENANT_CONTEXT.set(1, 1, 1, "owner", SCRATCH_CATALOG);
        assertThrows(SQLException.class, () -> {
            try (Connection connection = routing.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeQuery("SELECT * FROM this_table_does_not_exist");
                }
            }
        });

        TENANT_CONTEXT.clear();
        try (Connection connection = routing.getConnection()) {
            assertEquals(defaultCatalog, currentDatabase(connection));
        }
    }

    @Test
    void hikariDirtyBitAloneRestoresTheCatalogWhenArmed() throws SQLException {
        long dirtiedConnectionId;
        try (Connection connection = pool.getConnection()) {
            dirtiedConnectionId = connectionId(connection);
            connection.setCatalog(SCRATCH_CATALOG);
            assertEquals(SCRATCH_CATALOG, currentDatabase(connection));
        }

        try (Connection connection = pool.getConnection()) {
            assertEquals(dirtiedConnectionId, connectionId(connection));
            assertEquals(defaultCatalog, currentDatabase(connection));
        }
    }

    @Test
    void sharedContextStaysOnTheDefaultCatalog() throws SQLException {
        TENANT_CONTEXT.set(1, 1, 1, "owner", null);
        try (Connection connection = routing.getConnection()) {
            assertEquals(defaultCatalog, currentDatabase(connection));
        }
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT CONNECTION_ID()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String catalogFromUrl(String jdbcUrl) {
        int slash = jdbcUrl.lastIndexOf('/');
        int query = jdbcUrl.indexOf('?', slash);
        return query < 0 ? jdbcUrl.substring(slash + 1) : jdbcUrl.substring(slash + 1, query);
    }
}
