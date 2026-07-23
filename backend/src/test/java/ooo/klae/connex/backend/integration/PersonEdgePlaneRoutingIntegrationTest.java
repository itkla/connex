package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.PersonEdgeReadService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves PersonEdge visibility reads control metadata outside the tenant catalog. */
class PersonEdgePlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static PersonEdgeReadService personEdgeReader;
    private static SqlSessionFactory sqlSessionFactory;
    private static int orgId;
    private static int workspaceId;
    private static int siblingWorkspaceId;
    private static int foreignOrgId;
    private static int foreignWorkspaceId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping PersonEdge routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "cnx_person_edge_it_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace"),
                "Default catalog is not migrated; skipping PersonEdge routing test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            createTenantTables(statement);
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
        pool.setPoolName("person-edge-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        sqlSessionFactory = sqlSessionFactory(routing);

        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        when(workspaceMapper.getOrgId(anyInt())).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            int requestedWorkspaceId = invocation.getArgument(0);
            return withSession(session -> session.getMapper(WorkspaceMapper.class)
                .getOrgId(requestedWorkspaceId));
        });
        when(workspaceMapper.findByOrgId(anyInt())).thenAnswer(invocation -> {
            assertNull(tenantContext.getCatalog());
            int requestedOrgId = invocation.getArgument(0);
            return withSession(session -> session.getMapper(WorkspaceMapper.class)
                .findByOrgId(requestedOrgId));
        });
        PersonEdgeMapper personEdgeMapper = mock(PersonEdgeMapper.class);
        when(personEdgeMapper.getConnections(anyInt(), anyInt(), anyString()))
            .thenAnswer(invocation -> {
                int requestedWorkspaceId = invocation.getArgument(0);
                int requestedPersonId = invocation.getArgument(1);
                String workspaceIdsJson = invocation.getArgument(2);
                return withSession(session -> session.getMapper(PersonEdgeMapper.class)
                    .getConnections(requestedWorkspaceId, requestedPersonId, workspaceIdsJson));
            });
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), workspaceMapper);
        OrganizationWorkspaceScopeControlAccess controlAccess =
            new OrganizationWorkspaceScopeControlAccess(
                new OrganizationWorkspaceScopeControlOperations(workspaceMapper),
                tenantWorkScope, tenantContext,
                mock(PlatformTransactionManager.class));
        personEdgeReader = new PersonEdgeReadService(personEdgeMapper, controlAccess);
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
                statement.executeUpdate("DELETE FROM workspace WHERE id IN ("
                    + workspaceId + ", " + siblingWorkspaceId + ", " + foreignWorkspaceId + ")");
            }
            if (orgId != 0) {
                statement.executeUpdate("DELETE FROM organization WHERE id IN (" + orgId + ", " + foreignOrgId + ")");
            }
            if (scratchCatalogCreated) {
                statement.execute("DROP DATABASE " + scratchCatalog);
            }
        }
    }

    @Test
    void sharedConnectionUsesControlOrgScopeAndTenantRows() throws SQLException {
        tenantContext.set(workspaceId, orgId, 42, "member", scratchCatalog);
        try {
            List<PersonConnectionDto> connections = personEdgeReader.getConnections(workspaceId, 101);

            assertEquals(2, connections.size());
            PersonConnectionDto shared = connection(connections, 102);
            assertEquals("Shared Target", shared.getPersonName());
            assertEquals(201, shared.getCompanyId());
            assertEquals("Shared Company", shared.getCompanyName());
            PersonConnectionDto forged = connection(connections, 103);
            assertEquals("Forged Company Target", forged.getPersonName());
            assertNull(forged.getCompanyId());
            assertNull(forged.getCompanyName());
            assertEquals(scratchCatalog,
                withSession(PersonEdgePlaneRoutingIntegrationTest::currentCatalog));
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "workspace"));
            }
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog,
            withSession(PersonEdgePlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void createTenantTables(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE " + scratchCatalog + ".person ("
            + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL, "
            + "company_id INT NULL, suspended_at DATETIME NULL, provision_ceased_at DATETIME NULL)");
        statement.execute("CREATE TABLE " + scratchCatalog + ".person_share ("
            + "person_id INT NOT NULL, workspace_id INT NOT NULL, PRIMARY KEY (person_id, workspace_id))");
        statement.execute("CREATE TABLE " + scratchCatalog + ".company ("
            + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL)");
        statement.execute("CREATE TABLE " + scratchCatalog + ".company_share ("
            + "company_id INT NOT NULL, workspace_id INT NOT NULL, PRIMARY KEY (company_id, workspace_id))");
        statement.execute("CREATE TABLE " + scratchCatalog + ".person_edge ("
            + "id INT PRIMARY KEY, workspace_id INT NOT NULL, source_person_id INT NOT NULL, "
            + "target_person_id INT NOT NULL, type VARCHAR(32) NOT NULL, strength INT NOT NULL, "
            + "note TEXT NULL, created_at DATETIME NOT NULL)");
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('PersonEdge Plane Org', "
                + "CONCAT('person-edge-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'PersonEdge Plane Workspace', CONCAT('person-edge-plane-ws-', UUID()))");
        siblingWorkspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'PersonEdge Plane Sibling', CONCAT('person-edge-plane-sibling-', UUID()))");
        foreignOrgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('PersonEdge Foreign Org', "
                + "CONCAT('person-edge-foreign-', UUID()))");
        foreignWorkspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + foreignOrgId
                + ", 'PersonEdge Foreign Workspace', CONCAT('person-edge-foreign-ws-', UUID()))");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".company "
                + "(id, workspace_id, name) VALUES "
                + "(201, " + siblingWorkspaceId + ", 'Shared Company'), "
                + "(202, " + foreignWorkspaceId + ", 'Forged Company')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".company_share "
                + "(company_id, workspace_id) VALUES "
                + "(201, " + workspaceId + "), (202, " + workspaceId + ")");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".person "
                + "(id, workspace_id, name, company_id) VALUES "
                + "(101, " + workspaceId + ", 'Owned Source', NULL), "
                + "(102, " + siblingWorkspaceId + ", 'Shared Target', 201), "
                + "(103, " + siblingWorkspaceId + ", 'Forged Company Target', 202)");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".person_share "
                + "(person_id, workspace_id) VALUES "
                + "(102, " + workspaceId + "), (103, " + workspaceId + ")");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".person_edge "
                + "(id, workspace_id, source_person_id, target_person_id, type, strength, note, created_at) "
                + "VALUES "
                + "(301, " + workspaceId + ", 101, 102, 'friend', 3, 'Trusted', NOW()), "
                + "(302, " + workspaceId + ", 101, 103, 'friend', 3, 'Forged company', NOW())");
        }
    }

    private static PersonConnectionDto connection(List<PersonConnectionDto> connections, int personId) {
        return connections.stream()
            .filter(connection -> connection.getPersonId() == personId)
            .findFirst()
            .orElseThrow();
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
            new Environment("person-edge-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/PersonEdgeMapper.xml", "mappers/WorkspaceMapper.xml")) {
            try (InputStream input = PersonEdgePlaneRoutingIntegrationTest.class
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
