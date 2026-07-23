package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import org.mockito.InOrder;
import org.mybatis.spring.SqlSessionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationScheduler;
import ooo.klae.connex.backend.services.NotificationReconciliationService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves notification workspace enumeration crosses from the control plane before tenant work. */
class NotificationSchedulerPlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static SqlSessionFactory sqlSessionFactory;
    private static NotificationScheduler scheduler;
    private static NotificationReconciliationService reconciliationService;
    private static int orgId;
    private static int workspaceId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping notification routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "connexdb_routing_notif_"
            + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace"),
                "Default catalog is not migrated; skipping notification routing test");
            try {
                statement.execute("CREATE DATABASE " + scratchCatalog);
            } catch (SQLException exception) {
                assumeTrue(false, "Cannot create scratch catalog " + scratchCatalog + " ("
                    + exception.getMessage() + "); grant the documented connexdb_routing_% privilege");
            }
            scratchCatalogCreated = true;
            insertFixtures(connection);
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(2_000);
        pool.setPoolName("notification-workspace-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        sqlSessionFactory = sqlSessionFactory(routing);
        WorkspaceMapper workspaceMapper = new SqlSessionTemplate(sqlSessionFactory)
            .getMapper(WorkspaceMapper.class);
        TenantCatalogResolver resolver = mock(TenantCatalogResolver.class);
        when(resolver.resolveCatalog(anyInt())).thenReturn(scratchCatalog);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, resolver, workspaceMapper);
        reconciliationService = mock(NotificationReconciliationService.class);
        scheduler = new NotificationScheduler(
            workspaceMapper, tenantWorkScope, reconciliationService);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (tenantContext != null) {
            tenantContext.clear();
        }
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
    void enumeratesDefaultCatalogThenRestoresTenantRoutingWithOneConnection() throws SQLException {
        AtomicReference<String> reconciliationCatalog = new AtomicReference<>();
        doAnswer(invocation -> {
            if (invocation.<Integer>getArgument(0) == workspaceId) {
                reconciliationCatalog.set(withSession(
                    NotificationSchedulerPlaneRoutingIntegrationTest::currentCatalog));
            }
            return null;
        }).when(reconciliationService).reconcileWorkspace(anyInt(), eq(true));
        tenantContext.set(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            "owner", scratchCatalog);

        try {
            scheduler.reconcileAndPurge();

            assertEquals(scratchCatalog, reconciliationCatalog.get());
            assertEquals(scratchCatalog,
                withSession(NotificationSchedulerPlaneRoutingIntegrationTest::currentCatalog));
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "workspace"));
            }
            InOrder order = inOrder(reconciliationService);
            order.verify(reconciliationService).reconcileWorkspace(workspaceId, true);
            order.verify(reconciliationService).purgeWorkspace(workspaceId);
        } finally {
            tenantContext.clear();
        }

        assertEquals(defaultCatalog,
            withSession(NotificationSchedulerPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Notification Routing Org', "
                + "CONCAT('notification-routing-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Notification Routing Workspace', CONCAT('notification-routing-ws-', UUID()))");
    }

    private static int insertAndReturnId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertNotNull(keys);
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String catalog, String table)
            throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(catalog, null, table, null)) {
            return tables.next();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment("notification-workspace-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        String resource = "mappers/WorkspaceMapper.xml";
        try (InputStream input = NotificationSchedulerPlaneRoutingIntegrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing mapper resource " + resource);
            }
            new XMLMapperBuilder(
                input, configuration, resource, configuration.getSqlFragments()).parse();
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
            throw new IllegalStateException(
                "Could not inspect the routed connection catalog", exception);
        }
    }
}
