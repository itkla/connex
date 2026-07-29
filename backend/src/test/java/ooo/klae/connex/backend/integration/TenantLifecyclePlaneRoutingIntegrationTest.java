package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves lifecycle control roots and tenant rows stay on their catalog planes. */
class TenantLifecyclePlaneRoutingIntegrationTest {
    private static final String SCRATCH_CATALOG = "connexdb_routing_lifecycle_it";

    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static TenantWorkScope tenantWorkScope;
    private static SqlSessionFactory sqlSessionFactory;
    private static int orgId;
    private static int workspaceId;
    private static int defaultPersonId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault(
            "CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping lifecycle routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(
                tableExists(connection, "tenant_operation_lease"),
                "Default catalog is not migrated; skipping lifecycle routing test");
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCRATCH_CATALOG);
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".company_tag");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".tag");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".company");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".person");
            statement.execute(
                "CREATE TABLE " + SCRATCH_CATALOG + ".person LIKE " + defaultCatalog + ".person");
            statement.execute(
                "CREATE TABLE " + SCRATCH_CATALOG + ".company LIKE " + defaultCatalog + ".company");
            statement.execute(
                "CREATE TABLE " + SCRATCH_CATALOG + ".tag LIKE " + defaultCatalog + ".tag");
            statement.execute(
                "CREATE TABLE " + SCRATCH_CATALOG
                    + ".company_tag LIKE " + defaultCatalog + ".company_tag");
            statement.execute(
                "ALTER TABLE " + SCRATCH_CATALOG + ".company_tag"
                    + " ADD CONSTRAINT fk_company_tag_company"
                    + " FOREIGN KEY (company_id) REFERENCES "
                    + SCRATCH_CATALOG + ".company(id) ON DELETE CASCADE");
            insertFixtures(connection);
        } catch (SQLException exception) {
            assumeTrue(
                false,
                "Cannot prepare scratch catalog " + SCRATCH_CATALOG
                    + " (" + exception.getMessage() + ")");
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setPoolName("tenant-lifecycle-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(
            pool,
            properties,
            tenantContext);
        tenantWorkScope = new TenantWorkScope(
            tenantContext,
            mock(TenantCatalogResolver.class),
            mock(WorkspaceMapper.class));
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
            if (defaultPersonId != 0) {
                statement.executeUpdate("DELETE FROM person WHERE id = " + defaultPersonId);
            }
            if (workspaceId != 0) {
                statement.executeUpdate("DELETE FROM workspace WHERE id = " + workspaceId);
            }
            if (orgId != 0) {
                statement.executeUpdate("DELETE FROM organization WHERE id = " + orgId);
            }
            statement.execute("DROP DATABASE IF EXISTS " + SCRATCH_CATALOG);
        }
    }

    @Test
    void controlLookupAndRegistryCountDeleteUseOppositeCatalogs() {
        tenantContext.set(workspaceId, orgId, 1, "org_admin", SCRATCH_CATALOG);

        WorkspaceLifecycleRef controlWorkspace = tenantWorkScope.unrouted(() ->
            withSession(session -> session
                .getMapper(TenantLifecycleControlMapper.class)
                .findWorkspaceInOrg(orgId, workspaceId)));
        long scratchPeople = withSession(session -> session
            .getMapper(TenantLifecycleMapper.class)
            .countRows(workspaceId, TenantLifecycleRegistry.require("person")));
        long scratchCompanyTags = withSession(session -> session
            .getMapper(TenantLifecycleMapper.class)
            .countRows(workspaceId, TenantLifecycleRegistry.require("company_tag")));

        assertNotNull(controlWorkspace);
        assertEquals(orgId, controlWorkspace.orgId());
        assertEquals(2, scratchPeople);
        assertEquals(1, scratchCompanyTags);

        int deletedCompanies = withSession(session -> session
            .getMapper(TenantLifecycleMapper.class)
            .deleteDirectBatch(
                workspaceId,
                TenantLifecycleRegistry.require("company"),
                10));

        assertEquals(1, deletedCompanies);
        assertEquals(0L, TenantLifecyclePlaneRoutingIntegrationTest.<Long>withSession(session -> session
            .getMapper(TenantLifecycleMapper.class)
            .countRows(workspaceId, TenantLifecycleRegistry.require("company_tag"))));
        tenantContext.clear();
        assertEquals(1L, TenantLifecyclePlaneRoutingIntegrationTest.<Long>withSession(session -> session
            .getMapper(TenantLifecycleMapper.class)
            .countRows(workspaceId, TenantLifecycleRegistry.require("person"))));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(
            connection,
            "INSERT INTO organization (name, slug)"
                + " VALUES ('Lifecycle Plane Org', CONCAT('lifecycle-plane-', UUID()))");
        workspaceId = insertAndReturnId(
            connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES ("
                + orgId
                + ", 'Lifecycle Plane Workspace', CONCAT('lifecycle-plane-ws-', UUID()))");
        defaultPersonId = insertAndReturnId(
            connection,
            "INSERT INTO person (workspace_id, name, email) VALUES ("
                + workspaceId
                + ", 'Default Lifecycle Person', 'default-lifecycle@example.com')");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + SCRATCH_CATALOG
                    + ".person (workspace_id, name, email) VALUES (?, ?, ?), (?, ?, ?)")) {
            statement.setInt(1, workspaceId);
            statement.setString(2, "Scratch Lifecycle Person One");
            statement.setString(3, "scratch-lifecycle-one@example.com");
            statement.setInt(4, workspaceId);
            statement.setString(5, "Scratch Lifecycle Person Two");
            statement.setString(6, "scratch-lifecycle-two@example.com");
            statement.executeUpdate();
        }
        int companyId = insertAndReturnId(
            connection,
            "INSERT INTO " + SCRATCH_CATALOG + ".company (workspace_id, name) VALUES ("
                + workspaceId + ", 'Scratch Lifecycle Company')");
        int tagId = insertAndReturnId(
            connection,
            "INSERT INTO " + SCRATCH_CATALOG + ".tag (workspace_id, name) VALUES ("
                + workspaceId + ", 'scratch-lifecycle-tag')");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + SCRATCH_CATALOG
                    + ".company_tag (company_id, tag_id) VALUES (?, ?)")) {
            statement.setInt(1, companyId);
            statement.setInt(2, tagId);
            statement.executeUpdate();
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

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                defaultCatalog,
                null,
                table,
                null)) {
            return tables.next();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(
            TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment(
                "tenant-lifecycle-plane-routing",
                new JdbcTransactionFactory(),
                routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/TenantLifecycleMapper.xml",
                "mappers/TenantLifecycleControlMapper.xml")) {
            try (InputStream input = TenantLifecyclePlaneRoutingIntegrationTest.class
                    .getClassLoader()
                    .getResourceAsStream(resource)) {
                assumeTrue(input != null, "Missing mapper resource " + resource);
                new XMLMapperBuilder(
                    input,
                    configuration,
                    resource,
                    configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static <T> T withSession(
            java.util.function.Function<SqlSession, T> work) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return work.apply(session);
        }
    }
}
