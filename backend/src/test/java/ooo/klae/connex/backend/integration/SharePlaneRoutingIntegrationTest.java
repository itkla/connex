package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess.OrganizationWorkspaces;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Proves record sharing works with the organization's data in its own catalog (#811): the genuine
 * grant and the genuine listing run against a tenant catalog that holds no {@code workspace} table
 * at all, while the organization ceiling, the workspace names and the listing order come from the
 * control catalog through {@link ShareWorkspaceControlAccess}.
 *
 * <p>#1793 recorded that the deal-collaborator routing test substituted a pure DELETE for the real
 * write and so could not have caught a write path still crossing the plane wall. This test calls
 * {@code ShareMapper.shareCompany} and {@code ShareMapper.listCompanyShares} themselves.
 */
class SharePlaneRoutingIntegrationTest {
    private static final String MAPPERS = "ooo.klae.connex.backend.mappers.";
    private static final String SHARE_MAPPER = MAPPERS + "ShareMapper";
    private static final String WORKSPACE_MAPPER = MAPPERS + "WorkspaceMapper";

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
    private static ShareMapper shareMapper;
    private static ShareWorkspaceControlAccess controlAccess;
    private static int orgId;
    private static int foreignOrgId;
    private static int ownerWorkspaceId;
    private static int zuluWorkspaceId;
    private static int alphaWorkspaceId;
    private static int foreignWorkspaceId;
    private static int grantedById;
    private static int companyId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping share plane routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "cnx_share_plane_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "company_share"),
                "Default catalog is not migrated; skipping share plane routing test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".company LIKE "
                + defaultCatalog + ".company");
            statement.execute("CREATE TABLE " + scratchCatalog + ".company_share LIKE "
                + defaultCatalog + ".company_share");
            insertFixtures(connection);
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(2);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("share-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        transactionManager = new DataSourceTransactionManager(routing);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory(routing));
        shareMapper = sqlSessionTemplate.getMapper(ShareMapper.class);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        controlAccess = new ShareWorkspaceControlAccess(
            sqlSessionTemplate.getMapper(WorkspaceMapper.class),
            tenantWorkScope, tenantContext, transactionManager);
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
            if (grantedById != 0) {
                statement.executeUpdate("DELETE FROM app_user WHERE id = " + grantedById);
            }
            for (int workspaceId : new int[] {
                    ownerWorkspaceId, zuluWorkspaceId, alphaWorkspaceId, foreignWorkspaceId}) {
                if (workspaceId != 0) {
                    statement.executeUpdate("DELETE FROM workspace WHERE id = " + workspaceId);
                }
            }
            for (int organizationId : new int[] {orgId, foreignOrgId}) {
                if (organizationId != 0) {
                    statement.executeUpdate("DELETE FROM organization WHERE id = " + organizationId);
                }
            }
            if (scratchCatalogCreated) {
                statement.execute("DROP DATABASE " + scratchCatalog);
            }
        }
    }

    @BeforeEach
    void clearTenantShares() throws SQLException {
        assumeTrue(scratchCatalogCreated, "Scratch catalog was not created");
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + scratchCatalog + ".company_share");
        }
    }

    @Test
    void theShareStatementsKeepThePlaneClassificationThisTestAssumes() {
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains(SHARE_MAPPER),
            "Share rows are tenant data and must stay behind the tenant backstop");
        assertTrue(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.contains(WORKSPACE_MAPPER),
            "The organization snapshot runs in an unrouted span with no tenant scope installed, "
                + "so WorkspaceMapper must stay exempt from the fail-closed tenant backstop");
        assertTrue(ControlCatalogRoutingInterceptor.CONTROL_CATALOG_NAMESPACES.contains(WORKSPACE_MAPPER),
            "The snapshot must be classified physically control-plane so it reads the default "
                + "catalog this test proves the tenant catalog does not hold");
        assertFalse(ControlCatalogRoutingInterceptor.CONTROL_CATALOG_NAMESPACES.contains(SHARE_MAPPER),
            "Share rows live in the tenant catalog, so ShareMapper must never route to control");
    }

    @Test
    void theTenantCatalogHoldsShareRowsButNoWorkspaceTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertTrue(tableExists(connection, scratchCatalog, "company_share"));
            assertFalse(tableExists(connection, scratchCatalog, "workspace"),
                "the point of this test is that the removed JOIN workspace could not have run here");
        }
    }

    @Test
    void transactionalGrantEnforcesTheControlAllowlistWithoutLeavingTheTenantCatalog()
            throws SQLException {
        tenantContext.set(ownerWorkspaceId, orgId, grantedById, "owner", scratchCatalog);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Connection tenantConnection = DataSourceUtils.getConnection(routing);
                assertEquals(scratchCatalog, catalogOf(tenantConnection));

                OrganizationWorkspaces organizationWorkspaces =
                    controlAccess.getForWorkspace(ownerWorkspaceId);

                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(scratchCatalog, tenantContext.getCatalog());
                assertSame(tenantConnection, DataSourceUtils.getConnection(routing));
                assertEquals(scratchCatalog, catalogOf(tenantConnection));
                assertEquals(
                    "[" + alphaWorkspaceId + "," + ownerWorkspaceId + "," + zuluWorkspaceId + "]",
                    organizationWorkspaces.workspaceIdsJson(),
                    "the allowlist is the owning organization's workspaces in name order");

                assertEquals(1, shareMapper.shareCompany(companyId, ownerWorkspaceId,
                    zuluWorkspaceId, grantedById, false,
                    organizationWorkspaces.workspaceIdsJson()));
                assertEquals(0, shareMapper.shareCompany(companyId, ownerWorkspaceId,
                    foreignWorkspaceId, grantedById, false,
                    organizationWorkspaces.workspaceIdsJson()),
                    "a target outside the organization snapshot must insert nothing");
                assertTrue(shareMapper.companyShareExists(
                    companyId, ownerWorkspaceId, zuluWorkspaceId));
                assertFalse(shareMapper.companyShareExists(
                    companyId, ownerWorkspaceId, foreignWorkspaceId));
            });

            assertEquals(List.of(zuluWorkspaceId), scratchShareWorkspaceIds());
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, currentCatalog());
    }

    @Test
    void listingHydratesWorkspaceNamesFromTheControlCatalogAndDropsStaleTargets()
            throws SQLException {
        insertScratchShare(zuluWorkspaceId);
        insertScratchShare(alphaWorkspaceId);
        insertScratchShare(foreignWorkspaceId);
        tenantContext.set(ownerWorkspaceId, orgId, grantedById, "owner", scratchCatalog);
        try {
            List<ShareDto> rows = shareMapper.listCompanyShares(ownerWorkspaceId, companyId);
            assertEquals(3, rows.size());
            assertNull(rows.getFirst().getWorkspaceName(),
                "the tenant catalog cannot name a workspace it does not store");

            List<ShareDto> hydrated =
                controlAccess.getForWorkspace(ownerWorkspaceId).hydrate(rows);

            assertEquals(List.of(alphaWorkspaceId, zuluWorkspaceId),
                hydrated.stream().map(ShareDto::getWorkspaceId).toList());
            assertEquals(List.of("Alpha Share Routing WS", "Zulu Share Routing WS"),
                hydrated.stream().map(ShareDto::getWorkspaceName).toList());
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog, currentCatalog());
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Share Plane Org', "
                + "CONCAT('share-plane-', UUID()))");
        foreignOrgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Share Plane Foreign Org', "
                + "CONCAT('share-plane-foreign-', UUID()))");
        ownerWorkspaceId = insertWorkspace(connection, orgId, "Owner Share Routing WS");
        zuluWorkspaceId = insertWorkspace(connection, orgId, "Zulu Share Routing WS");
        alphaWorkspaceId = insertWorkspace(connection, orgId, "Alpha Share Routing WS");
        foreignWorkspaceId = insertWorkspace(connection, foreignOrgId, "Foreign Share Routing WS");
        grantedById = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('share-plane-', UUID()), 'Share Plane Grantor', "
                + "CONCAT('share-plane-', UUID(), '@example.com'), 'routing-test-hash', 'UTC')");
        companyId = insertAndReturnId(connection,
            "INSERT INTO " + scratchCatalog + ".company (workspace_id, name) VALUES ("
                + ownerWorkspaceId + ", 'Share Plane Company')");
    }

    private static int insertWorkspace(Connection connection, int organizationId, String name)
            throws SQLException {
        return insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + organizationId + ", '" + name
                + "', CONCAT('share-plane-ws-', UUID()))");
    }

    private static void insertScratchShare(int targetWorkspaceId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + scratchCatalog
                + ".company_share (company_id, workspace_id, granted_by, can_edit) VALUES ("
                + companyId + ", " + targetWorkspaceId + ", " + grantedById + ", 0)");
        }
    }

    private static List<Integer> scratchShareWorkspaceIds() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT workspace_id FROM " + scratchCatalog
                    + ".company_share WHERE company_id = " + companyId + " ORDER BY workspace_id")) {
            List<Integer> workspaceIds = new ArrayList<>();
            while (rows.next()) {
                workspaceIds.add(rows.getInt(1));
            }
            return List.copyOf(workspaceIds);
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

    private static boolean tableExists(Connection connection, String catalog, String table)
            throws SQLException {
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
            "share-plane-routing", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/ShareMapper.xml", "mappers/WorkspaceMapper.xml")) {
            try (InputStream input = SharePlaneRoutingIntegrationTest.class
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
