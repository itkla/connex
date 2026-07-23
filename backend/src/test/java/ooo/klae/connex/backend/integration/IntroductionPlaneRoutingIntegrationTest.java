package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

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

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.IntroductionDto;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves introduction lineage and user labels execute against their respective data planes. */
class IntroductionPlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static TenantWorkScope tenantWorkScope;
    private static SqlSessionFactory sqlSessionFactory;
    private static int orgId;
    private static int workspaceId;
    private static int userId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping Introduction plane-routing integration test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "connexdb_routing_intro_it_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace"),
                "Default catalog is not migrated; skipping Introduction plane-routing integration test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".company ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".person ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, company_id INT NULL, "
                + "name VARCHAR(255) NOT NULL, image_url VARCHAR(2048) NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".introduction ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, introducer_user_id INT NULL, "
                + "person_a_id INT NOT NULL, person_b_id INT NOT NULL, status VARCHAR(32) NOT NULL, "
                + "note TEXT NULL, introduced_at DATETIME NOT NULL)");
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
        pool.setPoolName("introduction-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
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
            if (userId != 0) {
                statement.executeUpdate("DELETE FROM app_user WHERE id = " + userId);
            }
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
    void lineageAndUserLabelsReadFromSeparateCatalogs() throws SQLException {
        tenantContext.set(workspaceId, orgId, userId, "org_admin", scratchCatalog);
        try {
            List<IntroductionDto> lineage = withSession(session -> session.getMapper(IntroductionMapper.class)
                .findLineage(workspaceId, 10, 0));
            List<UserDisplayNameDto> labels = tenantWorkScope.unrouted(() -> withSession(session ->
                session.getMapper(UserMapper.class).getDisplayNamesByIds(List.of(userId))));

            assertEquals(1, lineage.size());
            assertEquals(userId, lineage.getFirst().getIntroducerId());
            assertNull(lineage.getFirst().getIntroducerName());
            assertEquals(List.of(new UserDisplayNameDto(userId, "Control Introducer")), labels);
            assertEquals(scratchCatalog, withSession(IntroductionPlaneRoutingIntegrationTest::currentCatalog));
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "app_user"));
            }
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, withSession(IntroductionPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Introduction Plane Org', "
                + "CONCAT('intro-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Introduction Plane Workspace', CONCAT('intro-plane-ws-', UUID()))");
        userId = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('intro-plane-', UUID()), 'Control Introducer', CONCAT(UUID(), '@example.com'), "
                + "'routing-test-hash', 'UTC')");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".company "
                + "(id, workspace_id, name) VALUES (701, " + workspaceId + ", 'Scratch Company')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".person "
                + "(id, workspace_id, company_id, name, image_url) VALUES "
                + "(501, " + workspaceId + ", 701, 'Scratch First', NULL), "
                + "(502, " + workspaceId + ", 701, 'Scratch Second', NULL)");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".introduction "
                + "(id, workspace_id, introducer_user_id, person_a_id, person_b_id, status, note, introduced_at) "
                + "VALUES (1, " + workspaceId + ", " + userId
                + ", 501, 502, 'made', 'Scratch lineage', '2026-07-01 00:00:00')");
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
            new Environment("introduction-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        for (String resource : List.of("mappers/IntroductionMapper.xml", "mappers/UserMapper.xml")) {
            try (InputStream input = IntroductionPlaneRoutingIntegrationTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                assumeTrue(input != null, "Missing mapper resource " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
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
