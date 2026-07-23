package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.DealCollaboratorControlAccess;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves collaborator relationships and member profiles execute against separate catalogs. */
class DealCollaboratorPlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static DealCollaboratorControlAccess controlAccess;
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
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping deal collaborator routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "cnx_deal_collab_it_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace_member"),
                "Default catalog is not migrated; skipping deal collaborator routing test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".deal_collaborator ("
                + "workspace_id INT NOT NULL, deal_id INT NOT NULL, user_id INT NOT NULL, "
                + "PRIMARY KEY (workspace_id, deal_id, user_id))");
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
        pool.setPoolName("deal-collaborator-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        sqlSessionFactory = sqlSessionFactory(routing);
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.getWorkspaceProfileHydrationRowsByIds(anyInt(), anyList())).thenAnswer(invocation -> {
            int requestedWorkspaceId = invocation.getArgument(0);
            List<Integer> requestedUserIds = invocation.getArgument(1);
            return withSession(session -> session.getMapper(UserMapper.class)
                .getWorkspaceProfileHydrationRowsByIds(requestedWorkspaceId, requestedUserIds));
        });
        controlAccess = new DealCollaboratorControlAccess(
            userMapper, tenantWorkScope, tenantContext, mock(PlatformTransactionManager.class));
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
            if (workspaceId != 0 && userId != 0) {
                statement.executeUpdate("DELETE FROM workspace_member WHERE workspace_id = "
                    + workspaceId + " AND user_id = " + userId);
            }
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
    void collaboratorIdsAndProfilesReadFromSeparateCatalogs() throws SQLException {
        tenantContext.set(workspaceId, orgId, userId, "member", scratchCatalog);
        try {
            List<Integer> collaboratorIds = withSession(session -> session.getMapper(DealMapper.class)
                .getCollaboratorIds(workspaceId, 501));
            List<UserDto> profiles = controlAccess.getProfiles(workspaceId, collaboratorIds);

            assertEquals(List.of(userId), collaboratorIds);
            assertEquals(1, profiles.size());
            assertEquals(userId, profiles.getFirst().getId());
            assertEquals("Control Collaborator", profiles.getFirst().getDisplayName());
            assertEquals("control-collaborator@example.com", profiles.getFirst().getEmail());
            assertEquals(scratchCatalog, withSession(DealCollaboratorPlaneRoutingIntegrationTest::currentCatalog));
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "app_user"));
                assertFalse(tableExists(connection, scratchCatalog, "workspace_member"));
            }
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, withSession(DealCollaboratorPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Deal Collaborator Plane Org', "
                + "CONCAT('deal-collab-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Deal Collaborator Plane Workspace', CONCAT('deal-collab-plane-ws-', UUID()))");
        userId = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('deal-collab-', UUID()), 'Control Collaborator', "
                + "'control-collaborator@example.com', 'routing-test-hash', 'UTC')");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES ("
                + workspaceId + ", " + userId + ", 'member')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog
                + ".deal_collaborator (workspace_id, deal_id, user_id) VALUES ("
                + workspaceId + ", 501, " + userId + ")");
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
            new Environment("deal-collaborator-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/DealMapper.xml", "mappers/UserMapper.xml")) {
            try (InputStream input = DealCollaboratorPlaneRoutingIntegrationTest.class
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
