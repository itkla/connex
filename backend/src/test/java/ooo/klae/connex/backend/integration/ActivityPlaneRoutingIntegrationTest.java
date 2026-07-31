package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/** Proves ActivityMapper's company-scoring reads run in a tenant catalog without control tables. */
class ActivityPlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static SqlSessionFactory sqlSessionFactory;
    private static int orgId;
    private static int workspaceId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping Activity plane-routing integration test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "connexdb_routing_activity_it_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace"),
                "Default catalog is not migrated; skipping Activity plane-routing integration test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".deal ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, company_id INT NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".activity ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, type VARCHAR(32) NOT NULL, "
                + "subject VARCHAR(255) NOT NULL, notes TEXT NULL, person_id INT NULL, deal_id INT NULL, "
                + "created_by_id INT NOT NULL, timestamp DATETIME NOT NULL, "
                + "provider_owned BOOLEAN NOT NULL DEFAULT FALSE, provider_name VARCHAR(32) NULL, "
                + "provider_stream VARCHAR(32) NULL, provider_source_id VARCHAR(512) NULL, "
                + "provider_captured_at DATETIME(6) NULL, provider_visibility VARCHAR(32) NULL, "
                + "provider_admitted_fields_json JSON NULL, "
                + "provider_material_exclusions_json JSON NULL)");
            insertFixtures(connection);
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot prepare scratch catalog " + scratchCatalog + " ("
                + exception.getMessage() + ")");
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setPoolName("activity-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        sqlSessionFactory = sqlSessionFactory(routing);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (pool != null) {
            pool.close();
        }
        if (username == null || password == null || url == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            if (workspaceId != 0) {
                statement.executeUpdate("DELETE FROM workspace WHERE id = " + workspaceId);
            }
            if (orgId != 0) {
                statement.executeUpdate("DELETE FROM organization WHERE id = " + orgId);
            }
            if (scratchCatalogCreated) {
                statement.execute("DROP DATABASE " + scratchCatalog);
            }
        }
    }

    @Test
    void scoringReadsStayInsideTheWorkspaceCatalog() throws SQLException {
        tenantContext.set(workspaceId, orgId, 1, "org_admin", scratchCatalog);

        List<Integer> personActivityIds = withSession(session -> session.getMapper(ActivityMapper.class)
            .getActivitiesByPersonIds(workspaceId, List.of(501)))
            .stream().map(Activity::getId).toList();
        List<Integer> dealActivityIds = withSession(session -> session.getMapper(ActivityMapper.class)
            .getActivitiesByDealCompanyIds(workspaceId, List.of(701)))
            .stream().map(Activity::getId).toList();

        assertEquals(List.of(2, 1), personActivityIds);
        assertEquals(List.of(2), dealActivityIds);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertFalse(tableExists(connection, scratchCatalog, "workspace"));
        }
        tenantContext.clear();
        assertEquals(defaultCatalog, withSession(ActivityPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Activity Plane Org', CONCAT('activity-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Activity Plane Workspace', CONCAT('activity-plane-ws-', UUID()))");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".deal "
                + "(id, workspace_id, company_id) VALUES (601, " + workspaceId + ", 701), "
                + "(602, " + workspaceId + ", 702)");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".activity "
                + "(id, workspace_id, type, subject, person_id, deal_id, created_by_id, timestamp) VALUES "
                + "(1, " + workspaceId + ", 'call', 'person only', 501, NULL, 1, '2026-01-01 10:00:00'), "
                + "(2, " + workspaceId + ", 'meeting', 'person and deal', 501, 601, 1, '2026-01-02 10:00:00'), "
                + "(3, " + workspaceId + ", 'email', 'other company', 999, 602, 1, '2026-01-03 10:00:00'), "
                + "(4, " + (workspaceId + 1) + ", 'email', 'other workspace', 501, 601, 1, '2026-01-04 10:00:00')");
        }
    }

    private static int insertAndReturnId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String catalog, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(catalog, null, table, null)) {
            return tables.next();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment("activity-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("Activity", Activity.class);
        configuration.getTypeAliasRegistry().registerAlias("Deal", Deal.class);
        configuration.getTypeAliasRegistry().registerAlias("Person", Person.class);
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        String resource = "mappers/ActivityMapper.xml";
        try (InputStream input = ActivityPlaneRoutingIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assumeTrue(input != null, "Missing mapper resource " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static <T> T withSession(java.util.function.Function<SqlSession, T> work) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return work.apply(session);
        }
    }

    private static String currentCatalog(SqlSession session) {
        try {
            return session.getConnection().getCatalog();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect the routed connection catalog", exception);
        }
    }
}
