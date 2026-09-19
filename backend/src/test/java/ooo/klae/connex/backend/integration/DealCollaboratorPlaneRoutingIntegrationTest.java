package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * Proves collaborator relationships and collaborator profiles are read from separate catalogs, and
 * that a transactional replacement hydrates control profiles without changing the tenant
 * transaction's catalog.
 */
class DealCollaboratorPlaneRoutingIntegrationTest {
    private static final int DEAL_ID = 501;
    private static final int MISSING_USER_ID = Integer.MAX_VALUE;

    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantRoutingDataSource routing;
    private static DataSourceTransactionManager transactionManager;
    private static TenantContext tenantContext;
    private static DealMapper dealMapper;
    private static DealCollaboratorControlAccess controlAccess;
    private static int orgId;
    private static int workspaceId;
    private static int alphaId;
    private static int zuluId;
    private static int pendingId;

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
        scratchCatalog = "cnx_deal_collab_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace_member"),
                "Default catalog is not migrated; skipping deal collaborator routing test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".deal_collaborator LIKE "
                + defaultCatalog + ".deal_collaborator");
            insertFixtures(connection);
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(2);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("deal-collaborator-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        transactionManager = new DataSourceTransactionManager(routing);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory(routing));
        dealMapper = sqlSessionTemplate.getMapper(DealMapper.class);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        controlAccess = new DealCollaboratorControlAccess(
            sqlSessionTemplate.getMapper(UserMapper.class), tenantWorkScope, tenantContext, transactionManager);
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
                statement.executeUpdate("DELETE FROM workspace_member WHERE workspace_id = " + workspaceId);
            }
            for (int userId : new int[] {alphaId, zuluId, pendingId}) {
                if (userId != 0) {
                    statement.executeUpdate("DELETE FROM app_user WHERE id = " + userId);
                }
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
    void nonTransactionalReadHydratesActiveProfilesFromTheControlCatalog() throws SQLException {
        tenantContext.set(workspaceId, orgId, alphaId, "member", scratchCatalog);
        try {
            List<Integer> collaboratorIds = dealMapper.getCollaboratorIds(workspaceId, DEAL_ID);

            List<UserDto> profiles = controlAccess.getProfiles(workspaceId, collaboratorIds);

            assertEquals(List.of(alphaId, zuluId, pendingId, MISSING_USER_ID), collaboratorIds);
            assertEquals(List.of(alphaId, zuluId), profiles.stream().map(UserDto::getId).toList());
            assertEquals("Alpha Routing", profiles.getFirst().getDisplayName());
            assertEquals("alpha-routing@example.com", profiles.getFirst().getEmail());
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "app_user"));
                assertFalse(tableExists(connection, scratchCatalog, "workspace_member"));
            }
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, currentCatalog());
    }

    @Test
    void transactionalReplacementHydratesControlProfilesAndKeepsTheTenantCatalog() throws SQLException {
        tenantContext.set(workspaceId, orgId, alphaId, "member", scratchCatalog);
        try {
            List<UserDto> profiles = new TransactionTemplate(transactionManager).execute(status -> {
                Connection tenantConnection = DataSourceUtils.getConnection(routing);
                assertEquals(scratchCatalog, catalogOf(tenantConnection));
                assertEquals(1, dealMapper.removeCollaborator(workspaceId, DEAL_ID, zuluId));
                List<Integer> after = dealMapper.getCollaboratorIds(workspaceId, DEAL_ID);
                assertEquals(List.of(alphaId, pendingId, MISSING_USER_ID), after);

                List<UserDto> hydrated = controlAccess.getProfiles(workspaceId, after);

                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(scratchCatalog, tenantContext.getCatalog());
                assertSame(tenantConnection, DataSourceUtils.getConnection(routing));
                assertEquals(scratchCatalog, catalogOf(tenantConnection));
                assertEquals(after, dealMapper.getCollaboratorIds(workspaceId, DEAL_ID));
                status.setRollbackOnly();
                return hydrated;
            });

            assertEquals(List.of(alphaId), profiles.stream().map(UserDto::getId).toList());
            assertEquals("Alpha Routing", profiles.getFirst().getDisplayName());
            assertEquals(4, scratchCollaboratorCount());
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, currentCatalog());
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Deal Collaborator Plane Org', "
                + "CONCAT('deal-collab-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Deal Collaborator Plane Workspace', CONCAT('deal-collab-plane-ws-', UUID()))");
        alphaId = insertUser(connection, "Alpha Routing", "alpha-routing@example.com");
        zuluId = insertUser(connection, "Zulu Routing", "zulu-routing@example.com");
        pendingId = insertUser(connection, "Pending Routing", "pending-routing@example.com");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES ("
                + workspaceId + ", " + alphaId + ", 'member', 'active'), ("
                + workspaceId + ", " + zuluId + ", 'member', 'active'), ("
                + workspaceId + ", " + pendingId + ", 'member', 'pending')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog
                + ".deal_collaborator (workspace_id, deal_id, user_id) VALUES ("
                + workspaceId + ", " + DEAL_ID + ", " + alphaId + "), ("
                + workspaceId + ", " + DEAL_ID + ", " + zuluId + "), ("
                + workspaceId + ", " + DEAL_ID + ", " + pendingId + "), ("
                + workspaceId + ", " + DEAL_ID + ", " + MISSING_USER_ID + ")");
        }
    }

    private static int insertUser(Connection connection, String displayName, String email) throws SQLException {
        return insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('deal-collab-', UUID()), '" + displayName + "', '" + email
                + "', 'routing-test-hash', 'UTC')");
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

    private static int scratchCollaboratorCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + scratchCatalog
                    + ".deal_collaborator WHERE workspace_id = " + workspaceId + " AND deal_id = " + DEAL_ID)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String catalog, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(catalog, null, table, null)) {
            return tables.next();
        }
    }

    private static String catalogOf(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect the routed connection catalog", exception);
        }
    }

    private static String currentCatalog() throws SQLException {
        try (Connection connection = routing.getConnection()) {
            return connection.getCatalog();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource dataSource) throws Exception {
        Configuration configuration = new Configuration(new Environment(
            "deal-collaborator-plane-routing", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/PersonMapper.xml", "mappers/DealMapper.xml", "mappers/UserMapper.xml")) {
            try (InputStream input = DealCollaboratorPlaneRoutingIntegrationTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
