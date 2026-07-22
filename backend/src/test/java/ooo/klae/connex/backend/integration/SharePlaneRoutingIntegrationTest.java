package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.ShareControlOperations;
import ooo.klae.connex.backend.services.ShareService;
import ooo.klae.connex.backend.services.ShareTenantOperations;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves share metadata and tenant share rows execute against opposite real MySQL catalogs. */
class SharePlaneRoutingIntegrationTest {
    private static final String SCRATCH_CATALOG = "connexdb_routing_share_it";

    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static TenantWorkScope tenantWorkScope;
    private static SqlSessionFactory sqlSessionFactory;
    private static PlatformTransactionManager transactionManager;
    private static ShareService shareService;
    private static int orgId;
    private static int foreignOrgId;
    private static int ownerWorkspaceId;
    private static int targetWorkspaceId;
    private static int foreignWorkspaceId;
    private static int companyId;
    private static int serviceCompanyId;
    private static int personId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping share plane-routing integration test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, "workspace") && tableExists(connection, "company_share"),
                "Default catalog is not migrated; skipping share plane-routing integration test");
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCRATCH_CATALOG);
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".workspace");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".person_share");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".person");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".company_share");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".company");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".company LIKE "
                + defaultCatalog + ".company");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".company_share LIKE "
                + defaultCatalog + ".company_share");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".person LIKE "
                + defaultCatalog + ".person");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".person_share LIKE "
                + defaultCatalog + ".person_share");
            insertFixtures(connection);
        } catch (SQLException exception) {
            assumeTrue(false, "Cannot prepare scratch catalog " + SCRATCH_CATALOG + " ("
                + exception.getMessage() + ")");
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(4);
        pool.setPoolName("share-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        sqlSessionFactory = sqlSessionFactory(routing);
        transactionManager = new DataSourceTransactionManager(routing);
        shareService = shareService(transactionManager);
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
            statement.execute("DROP DATABASE IF EXISTS " + SCRATCH_CATALOG);
            deleteById(statement, "workspace", foreignWorkspaceId);
            deleteById(statement, "workspace", targetWorkspaceId);
            deleteById(statement, "workspace", ownerWorkspaceId);
            deleteById(statement, "organization", foreignOrgId);
            deleteById(statement, "organization", orgId);
        }
    }

    @AfterEach
    void clearTenantContext() {
        if (tenantContext != null) {
            tenantContext.clear();
        }
    }

    @Test
    void controlWorkspaceSnapshotGatesTenantShareRowsWithoutTenantWorkspaceTable() {
        tenantContext.set(ownerWorkspaceId, orgId, 1, "owner", SCRATCH_CATALOG);

        List<Workspace> workspaces = tenantWorkScope.unrouted(() -> withSession(session ->
            session.getMapper(WorkspaceMapper.class).findByOrgId(orgId)));
        List<Integer> workspaceIds = workspaces.stream().map(Workspace::getId).toList();
        int granted = tenantWorkScope.inWorkspace(ownerWorkspaceId, () -> withSession(session ->
            session.getMapper(ShareMapper.class).shareCompany(companyId, ownerWorkspaceId,
                targetWorkspaceId, 1, false, workspaceIds)));
        int refused = tenantWorkScope.inWorkspace(ownerWorkspaceId, () -> withSession(session ->
            session.getMapper(ShareMapper.class).shareCompany(companyId, ownerWorkspaceId,
                foreignWorkspaceId, 1, false, workspaceIds)));
        Person provisionState = tenantWorkScope.inWorkspace(ownerWorkspaceId, () -> withSession(session ->
            session.getMapper(ShareMapper.class)
                .getOwnedPersonProvisionState(ownerWorkspaceId, personId)));
        int personGranted = tenantWorkScope.inWorkspace(ownerWorkspaceId, () -> withSession(session ->
            session.getMapper(ShareMapper.class).sharePerson(personId, ownerWorkspaceId,
                targetWorkspaceId, 1, false, workspaceIds)));
        List<ShareDto> rows = tenantWorkScope.inWorkspace(ownerWorkspaceId, () -> withSession(session ->
            session.getMapper(ShareMapper.class).listCompanyShares(ownerWorkspaceId, companyId)));

        assertEquals(List.of(ownerWorkspaceId, targetWorkspaceId), workspaceIds);
        assertEquals(1, granted);
        assertEquals(0, refused);
        assertEquals(personId, provisionState.getId());
        assertNull(provisionState.getProvisionCeasedAt());
        assertEquals(1, personGranted);
        assertEquals(1, rows.size());
        assertEquals(targetWorkspaceId, rows.getFirst().getWorkspaceId());
        assertNull(rows.getFirst().getWorkspaceName());
        boolean tenantWorkspaceTableAbsent = tenantWorkScope.inWorkspace(
            ownerWorkspaceId,
            () -> withSession(session -> !tableExistsInCurrentCatalog(session, "workspace")));
        assertTrue(tenantWorkspaceTableAbsent);
        tenantContext.clear();
        assertEquals(defaultCatalog, withSession(SharePlaneRoutingIntegrationTest::currentCatalog));
    }

    @Test
    void annotatedServiceSuspendsAmbientTransactionsAndRunsEachPhaseInItsCatalog() {
        tenantContext.set(ownerWorkspaceId, orgId, 1, "owner", SCRATCH_CATALOG);
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            shareService.share("company", serviceCompanyId, targetWorkspaceId, true);
            status.setRollbackOnly();
        });

        List<ShareDto> shares = shareService.listShares("company", serviceCompanyId);
        assertEquals(1, shares.size());
        assertEquals(targetWorkspaceId, shares.getFirst().getWorkspaceId());
        assertEquals("Alpha Share Target", shares.getFirst().getWorkspaceName());
        assertTrue(shares.getFirst().isCanEdit());

        shareService.unshare("company", serviceCompanyId, targetWorkspaceId);
        assertTrue(shareService.listShares("company", serviceCompanyId).isEmpty());
        tenantContext.clear();
        assertEquals(defaultCatalog, withSession(SharePlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Share Plane Org', CONCAT('share-plane-', UUID()))");
        foreignOrgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES "
                + "('Share Plane Foreign Org', CONCAT('share-plane-foreign-', UUID()))");
        ownerWorkspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Zulu Share Owner', CONCAT('share-plane-owner-', UUID()))");
        targetWorkspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Alpha Share Target', CONCAT('share-plane-target-', UUID()))");
        foreignWorkspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + foreignOrgId
                + ", 'Foreign Share Target', CONCAT('share-plane-foreign-target-', UUID()))");
        companyId = insertAndReturnId(connection,
            "INSERT INTO " + SCRATCH_CATALOG + ".company (workspace_id, name) VALUES ("
                + ownerWorkspaceId + ", 'Scratch Share Company')");
        serviceCompanyId = insertAndReturnId(connection,
            "INSERT INTO " + SCRATCH_CATALOG + ".company (workspace_id, name) VALUES ("
                + ownerWorkspaceId + ", 'Scratch Service Share Company')");
        personId = insertAndReturnId(connection,
            "INSERT INTO " + SCRATCH_CATALOG + ".person (workspace_id, name, email) VALUES ("
                + ownerWorkspaceId + ", 'Scratch Share Person', 'scratch-share@example.com')");
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

    private static void deleteById(Statement statement, String table, int id) throws SQLException {
        if (id != 0) {
            statement.executeUpdate("DELETE FROM " + table + " WHERE id = " + id);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(defaultCatalog, null, table, null)) {
            return tables.next();
        }
    }

    private static boolean tableExistsInCurrentCatalog(SqlSession session, String table) {
        try {
            String catalog = session.getConnection().getCatalog();
            try (ResultSet tables = session.getConnection().getMetaData().getTables(catalog, null, table, null)) {
                return tables.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not inspect routed catalog tables", exception);
        }
    }

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment("share-plane-routing", new SpringManagedTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        configuration.getTypeAliasRegistry().registerAlias("Person", Person.class);
        configuration.getTypeAliasRegistry().registerAlias("Workspace", Workspace.class);
        configuration.getTypeAliasRegistry().registerAlias("WorkspaceMember", WorkspaceMember.class);
        for (String resource : List.of("mappers/WorkspaceMapper.xml", "mappers/ShareMapper.xml")) {
            try (InputStream input = SharePlaneRoutingIntegrationTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                assumeTrue(input != null, "Missing mapper resource " + resource);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static ShareService shareService(PlatformTransactionManager manager) {
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);
        ShareMapper shareMapper = template.getMapper(ShareMapper.class);
        WorkspaceMapper workspaceMapper = template.getMapper(WorkspaceMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuditService auditService = mock(AuditService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(ownerWorkspaceId);
        when(workspaceService.getCurrentUserId()).thenReturn(1);
        when(workspaceService.getOrgId(ownerWorkspaceId)).thenReturn(orgId);
        doAnswer(invocation -> {
            assertControlTransaction();
            return null;
        }).when(workspaceService).requirePermission(ownerWorkspaceId, 1, Permission.SHARE_MANAGE);
        doAnswer(invocation -> {
            assertControlTransaction();
            return null;
        }).when(workspaceService).requireMember(targetWorkspaceId, 1);
        doAnswer(invocation -> {
            assertControlTransaction();
            return null;
        }).when(auditService).recordScoped(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.eq(ownerWorkspaceId),
            org.mockito.ArgumentMatchers.eq(orgId),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull());
        ShareControlOperations controlOperations = transactionalProxy(
            new ShareControlOperations(workspaceService, workspaceMapper, auditService), manager,
            ShareControlOperations.class);
        ShareTenantOperations tenantOperations = transactionalProxy(
            new ShareTenantOperations(shareMapper), manager, ShareTenantOperations.class);
        return transactionalProxy(
            new ShareService(tenantOperations, controlOperations, tenantWorkScope),
            manager, ShareService.class);
    }

    private static <T> T transactionalProxy(T target, PlatformTransactionManager manager, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
            (org.springframework.transaction.TransactionManager) manager,
            new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    private static void assertControlTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(tenantContext.getCatalog());
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
            throw new IllegalStateException("Could not inspect routed connection catalog", exception);
        }
    }
}
