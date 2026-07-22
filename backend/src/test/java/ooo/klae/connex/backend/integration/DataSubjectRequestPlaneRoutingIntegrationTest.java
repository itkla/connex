package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.mappers.DataSubjectDisclosureMapper;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves DSR control and disclosure mappers execute on opposite catalogs through one routed pool. */
class DataSubjectRequestPlaneRoutingIntegrationTest {
    private static final String SCRATCH_CATALOG = "connexdb_routing_dsr_it";

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
    private static int personId;
    private static long requestId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping DSR plane-routing integration test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, "data_subject_request"),
                "Default catalog is not migrated; skipping DSR plane-routing integration test");
            statement.execute("CREATE DATABASE IF NOT EXISTS " + SCRATCH_CATALOG);
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".person");
            statement.execute("DROP TABLE IF EXISTS " + SCRATCH_CATALOG + ".company");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".company LIKE " + defaultCatalog + ".company");
            statement.execute("CREATE TABLE " + SCRATCH_CATALOG + ".person LIKE " + defaultCatalog + ".person");
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
        pool.setMaximumPoolSize(1);
        pool.setPoolName("dsr-plane-routing-it");
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
            if (requestId != 0) {
                statement.executeUpdate("DELETE FROM data_subject_request WHERE id = " + requestId);
            }
            if (personId != 0) {
                statement.executeUpdate("DELETE FROM person WHERE id = " + personId);
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
    void controlRequestAndTenantPersonComeFromTheirOwnCatalogs() {
        tenantContext.set(workspaceId, orgId, 1, "org_admin", SCRATCH_CATALOG);

        DataSubjectRequest request = tenantWorkScope.unrouted(() -> withSession(session ->
            session.getMapper(DataSubjectRequestMapper.class).findById(orgId, requestId)));
        PersonDto person = tenantWorkScope.inWorkspace(workspaceId, () -> withSession(session ->
            session.getMapper(DataSubjectDisclosureMapper.class)
                .findPerson(workspaceId, personId, List.of(workspaceId))));

        assertEquals("Control Requester", request.getRequesterName());
        assertEquals("Scratch Subject", person.getName());
        assertNull(person.getCompanyId());
        tenantContext.clear();
        assertEquals(defaultCatalog, withSession(DataSubjectRequestPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('DSR Plane Org', CONCAT('dsr-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'DSR Plane Workspace', CONCAT('dsr-plane-ws-', UUID()))");
        personId = insertAndReturnId(connection,
            "INSERT INTO person (workspace_id, name, email) VALUES (" + workspaceId
                + ", 'Default Subject', 'default-subject@example.com')");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + SCRATCH_CATALOG
                    + ".person (id, workspace_id, name, email) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, personId);
            statement.setInt(2, workspaceId);
            statement.setString(3, "Scratch Subject");
            statement.setString(4, "scratch-subject@example.com");
            statement.executeUpdate();
        }
        requestId = insertAndReturnLong(connection,
            "INSERT INTO data_subject_request "
                + "(org_id, request_type, status, requester_name, subject_name, subject_workspace_id, "
                + "subject_person_id, received_at, identity_verified_at) VALUES (" + orgId
                + ", 'disclosure', 'received', 'Control Requester', 'Control Subject', " + workspaceId
                + ", " + personId + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    private static int insertAndReturnId(Connection connection, String sql) throws SQLException {
        return Math.toIntExact(insertAndReturnLong(connection, sql));
    }

    private static long insertAndReturnLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(defaultCatalog, null, table, null)) {
            return tables.next();
        }
    }

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment("dsr-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAlias("DataSubjectRequest", DataSubjectRequest.class);
        for (String resource : List.of(
                "mappers/DataSubjectRequestMapper.xml", "mappers/DataSubjectDisclosureMapper.xml")) {
            try (InputStream input = DataSubjectRequestPlaneRoutingIntegrationTest.class
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
