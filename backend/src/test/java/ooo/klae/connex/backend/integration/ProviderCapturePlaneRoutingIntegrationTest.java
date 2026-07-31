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

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderCaptureUserPolicy;
import ooo.klae.connex.backend.beans.ProviderCaptureWorkspacePolicy;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Proves account-level capture purge stays in the selected dedicated tenant catalog.
 */
class ProviderCapturePlaneRoutingIntegrationTest {
    private static final int USER_ID = 2_000_000_001;
    private static final int WORKSPACE_ID = 2_000_000_002;

    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb"
                + "?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "Database credentials are required for provider capture routing");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        scratchCatalog =
            "connexdb_routing_capture_it_"
                + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection =
                DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(
                tableExists(
                    connection,
                    defaultCatalog,
                    "provider_capture_user_policy"),
                "Default catalog is not migrated");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            createScratchTables(statement);
            insertScratchRows(statement);
            statement.executeUpdate(
                "INSERT INTO provider_capture_user_policy "
                    + "(workspace_id, user_id, provider, enabled, "
                    + "excluded_people_json, excluded_conversations_json) VALUES ("
                    + WORKSPACE_ID + ", " + USER_ID
                    + ", 'google', TRUE, JSON_ARRAY(), JSON_ARRAY())");
        } catch (SQLException exception) {
            assumeTrue(
                false,
                "Cannot prepare capture routing catalog: "
                    + exception.getMessage());
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setPoolName("provider-capture-plane-routing-it");
        TenantRoutingProperties properties =
            new TenantRoutingProperties();
        properties.setMode(
            TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing =
            TenantRoutingConfig.decorate(
                pool, properties, tenantContext);
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
        try (Connection connection =
                DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "DELETE FROM provider_capture_user_policy "
                    + "WHERE workspace_id = " + WORKSPACE_ID
                    + " AND user_id = " + USER_ID);
            if (scratchCatalogCreated) {
                statement.execute(
                    "DROP DATABASE " + scratchCatalog);
            }
        }
    }

    @Test
    void accountPurgeDeletesOnlyTheRoutedCatalog() throws SQLException {
        tenantContext.set(
            WORKSPACE_ID,
            1,
            USER_ID,
            "member",
            scratchCatalog);
        withSession(session -> {
            ProviderCapturePurgeService purgeService =
                new ProviderCapturePurgeService(
                    session.getMapper(ProviderCaptureMapper.class));
            purgeService.purgeAccountCatalog(USER_ID, "google");
            purgeService.clearAccountReferencesInCatalog(USER_ID);
            return null;
        });

        try (Connection connection =
                DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assertEquals(
                1,
                count(
                    statement,
                    defaultCatalog
                        + ".provider_capture_user_policy WHERE workspace_id = "
                        + WORKSPACE_ID + " AND user_id = " + USER_ID));
            assertEquals(
                0,
                count(
                    statement,
                    scratchCatalog
                        + ".provider_capture_user_policy"));
            assertEquals(
                0,
                count(
                    statement,
                    scratchCatalog
                        + ".provider_captured_interaction"));
            assertEquals(
                0,
                count(
                    statement,
                    scratchCatalog
                        + ".provider_capture_sync_state"));
            assertEquals(
                0,
                count(
                    statement,
                    scratchCatalog
                        + ".provider_participant_decision"));
            assertEquals(
                0,
                statement.executeUpdate(
                    "UPDATE " + scratchCatalog
                        + ".provider_capture_workspace_policy "
                        + "SET updated_by_user_id = NULL "
                        + "WHERE updated_by_user_id IS NOT NULL"));
            assertFalse(
                tableExists(
                    connection,
                    scratchCatalog,
                    "workspace"));
        }
    }

    private static void createScratchTables(
            Statement statement) throws SQLException {
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".activity (id INT PRIMARY KEY, workspace_id INT NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_captured_interaction ("
                + "id BIGINT PRIMARY KEY, workspace_id INT NOT NULL, "
                + "user_id INT NOT NULL, provider VARCHAR(16) NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_activity_projection ("
                + "workspace_id INT NOT NULL, interaction_id BIGINT NOT NULL, "
                + "activity_id INT NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_capture_sync_state ("
                + "user_id INT NOT NULL, provider VARCHAR(16) NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_capture_user_policy ("
                + "user_id INT NOT NULL, provider VARCHAR(16) NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_participant_decision ("
                + "user_id INT NOT NULL, provider VARCHAR(16) NOT NULL)");
        statement.execute(
            "CREATE TABLE " + scratchCatalog
                + ".provider_capture_workspace_policy ("
                + "updated_by_user_id INT NULL)");
    }

    private static void insertScratchRows(
            Statement statement) throws SQLException {
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".activity VALUES (1, " + WORKSPACE_ID + ")");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_captured_interaction VALUES ("
                + "10, " + WORKSPACE_ID + ", " + USER_ID
                + ", 'google')");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_activity_projection VALUES ("
                + WORKSPACE_ID + ", 10, 1)");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_capture_sync_state VALUES ("
                + USER_ID + ", 'google')");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_capture_user_policy VALUES ("
                + USER_ID + ", 'google')");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_participant_decision VALUES ("
                + USER_ID + ", 'google')");
        statement.executeUpdate(
            "INSERT INTO " + scratchCatalog
                + ".provider_capture_workspace_policy VALUES ("
                + USER_ID + ")");
    }

    private static int count(
            Statement statement, String table) throws SQLException {
        try (ResultSet resultSet =
                statement.executeQuery(
                    "SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static boolean tableExists(
            Connection connection,
            String catalog,
            String table) throws SQLException {
        try (ResultSet tables =
                connection.getMetaData()
                    .getTables(catalog, null, table, null)) {
            return tables.next();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(
            TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment(
                "provider-capture-plane-routing",
                new JdbcTransactionFactory(),
                routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias(
            "ProviderCaptureWorkspacePolicy",
            ProviderCaptureWorkspacePolicy.class);
        configuration.getTypeAliasRegistry().registerAlias(
            "ProviderCaptureUserPolicy",
            ProviderCaptureUserPolicy.class);
        configuration.getTypeAliasRegistry().registerAlias(
            "ProviderCaptureSyncState",
            ProviderCaptureSyncState.class);
        configuration.getTypeAliasRegistry().registerAlias(
            "ProviderCapturedInteraction",
            ProviderCapturedInteraction.class);
        configuration.getTypeAliasRegistry().registerAlias(
            "ProviderCapturedParticipant",
            ProviderCapturedParticipant.class);
        String resource = "mappers/ProviderCaptureMapper.xml";
        try (InputStream input =
                ProviderCapturePlaneRoutingIntegrationTest.class
                    .getClassLoader()
                    .getResourceAsStream(resource)) {
            assumeTrue(input != null, "Missing mapper " + resource);
            new XMLMapperBuilder(
                input,
                configuration,
                resource,
                configuration.getSqlFragments())
                .parse();
        }
        return new SqlSessionFactoryBuilder()
            .build(configuration);
    }

    private static <T> T withSession(
            java.util.function.Function<SqlSession, T> work) {
        try (SqlSession session =
                sqlSessionFactory.openSession(true)) {
            return work.apply(session);
        }
    }
}
