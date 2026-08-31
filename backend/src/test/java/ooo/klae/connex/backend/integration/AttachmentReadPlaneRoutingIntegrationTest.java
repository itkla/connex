package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.mybatis.spring.SqlSessionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AttachmentReadService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Proves attachment rows and their user labels are read from separate catalogs. */
class AttachmentReadPlaneRoutingIntegrationTest {
    private static String url;
    private static String username;
    private static String password;
    private static String defaultCatalog;
    private static String scratchCatalog;
    private static boolean scratchCatalogCreated;
    private static HikariDataSource pool;
    private static TenantContext tenantContext;
    private static SqlSessionFactory sqlSessionFactory;
    private static AttachmentReadService attachmentReadService;
    private static int orgId;
    private static int workspaceId;
    private static int uploaderId;
    private static int targetUserId;
    private static int nonmemberId;

    @BeforeAll
    static void setUp() throws Exception {
        url = System.getenv().getOrDefault("CONNEX_DB_URL",
            "jdbc:mysql://localhost:3306/connexdb?createDatabaseIfNotExist=true&sslMode=DISABLED");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(username != null && password != null,
            "CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; skipping Attachment read routing test");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        assumeTrue(defaultCatalog != null, "The test JDBC URL must name a default catalog");
        scratchCatalog = "cx_att_read_"
            + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(url, username, password);
                Statement statement = connection.createStatement()) {
            assumeTrue(tableExists(connection, defaultCatalog, "workspace"),
                "Default catalog is not migrated; skipping Attachment read routing test");
            statement.execute("CREATE DATABASE " + scratchCatalog);
            scratchCatalogCreated = true;
            statement.execute("CREATE TABLE " + scratchCatalog + ".company ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL, "
                + "archived_at DATETIME NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".person ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL, "
                + "archived_at DATETIME NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".deal ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".tag ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255) NOT NULL, "
                + "color VARCHAR(32) NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".attachment ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, entity_type VARCHAR(32) NOT NULL, "
                + "entity_id INT NOT NULL, file_name VARCHAR(255) NOT NULL, url VARCHAR(2048) NOT NULL, "
                + "content_type VARCHAR(255) NULL, size BIGINT NULL, uploaded_by_id INT NULL, "
                + "created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL)");
            statement.execute("CREATE TABLE " + scratchCatalog + ".attachment_tag ("
                + "attachment_id INT NOT NULL, tag_id INT NOT NULL, "
                + "PRIMARY KEY (attachment_id, tag_id))");
            statement.execute("CREATE TABLE " + scratchCatalog + ".note ("
                + "id INT PRIMARY KEY, workspace_id INT NOT NULL, visibility VARCHAR(16) NOT NULL, "
                + "author_id INT NOT NULL)");
            insertFixtures(connection);
        }

        tenantContext = new TenantContext();
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(2_000);
        pool.setPoolName("attachment-read-plane-routing-it");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        TenantRoutingDataSource routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        sqlSessionFactory = sqlSessionFactory(routing);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        attachmentReadService = new AttachmentReadService(
            sqlSessionTemplate.getMapper(AttachmentMapper.class),
            sqlSessionTemplate.getMapper(UserMapper.class),
            tenantWorkScope,
            mock(AuthService.class));
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
                statement.executeUpdate("DELETE FROM workspace_member WHERE workspace_id = "
                    + workspaceId);
            }
            if (uploaderId != 0) {
                statement.executeUpdate("DELETE FROM app_user WHERE id = " + uploaderId);
            }
            if (targetUserId != 0) {
                statement.executeUpdate("DELETE FROM app_user WHERE id = " + targetUserId);
            }
            if (nonmemberId != 0) {
                statement.executeUpdate("DELETE FROM app_user WHERE id = " + nonmemberId);
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
    void directReadsHydrateControlLabelsWithoutControlTablesInTenantCatalog() throws SQLException {
        tenantContext.set(workspaceId, orgId, uploaderId, "org_admin", scratchCatalog);
        try {
            List<Attachment> all = attachmentReadService.getAll(workspaceId, uploaderId);
            List<Attachment> byEntity = attachmentReadService.getByEntity(
                workspaceId, "user", targetUserId);
            Attachment byId = attachmentReadService.getById(workspaceId, 2);
            Attachment byUrl = attachmentReadService.getByUrl(
                workspaceId, "/attachments/control-target.pdf");

            assertEquals(List.of(1, 2, 3, 4), all.stream().map(Attachment::getId).toList());
            assertEquals("Control Uploader", all.get(0).getUploadedBy().getDisplayName());
            assertEquals("Scratch Company", all.get(0).getEntityLabel());
            assertEquals("Control Target", all.get(1).getEntityLabel());
            assertNull(all.get(2).getUploadedBy());
            assertEquals("Control Uploader", all.get(3).getUploadedBy().getDisplayName());
            assertNull(all.get(3).getEntityLabel());
            assertEquals(List.of(2), byEntity.stream().map(Attachment::getId).toList());
            assertEquals("Control Uploader", byEntity.getFirst().getUploadedBy().getDisplayName());
            assertEquals("Control Target", byEntity.getFirst().getEntityLabel());
            assertNotNull(byId);
            assertEquals("Control Target", byId.getEntityLabel());
            assertEquals(List.of("Routing Tag"), byId.getTags().stream().map(tag -> tag.getName()).toList());
            assertEquals(2, byUrl.getId());
            assertEquals(scratchCatalog,
                withSession(AttachmentReadPlaneRoutingIntegrationTest::currentCatalog));
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                assertFalse(tableExists(connection, scratchCatalog, "app_user"));
                assertFalse(tableExists(connection, scratchCatalog, "workspace"));
                assertFalse(tableExists(connection, scratchCatalog, "workspace_member"));
            }
        } finally {
            tenantContext.clear();
        }
        assertEquals(defaultCatalog,
            withSession(AttachmentReadPlaneRoutingIntegrationTest::currentCatalog));
    }

    private static void insertFixtures(Connection connection) throws SQLException {
        orgId = insertAndReturnId(connection,
            "INSERT INTO organization (name, slug) VALUES ('Attachment Read Plane Org', "
                + "CONCAT('attachment-read-plane-', UUID()))");
        workspaceId = insertAndReturnId(connection,
            "INSERT INTO workspace (org_id, name, slug) VALUES (" + orgId
                + ", 'Attachment Read Plane Workspace', CONCAT('attachment-read-plane-ws-', UUID()))");
        uploaderId = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('attachment-uploader-', UUID()), 'Control Uploader', "
                + "CONCAT(UUID(), '@example.com'), 'routing-test-hash', 'UTC')");
        targetUserId = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('attachment-target-', UUID()), 'Control Target', "
                + "CONCAT(UUID(), '@example.com'), 'routing-test-hash', 'UTC')");
        nonmemberId = insertAndReturnId(connection,
            "INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES ("
                + "CONCAT('attachment-nonmember-', UUID()), 'Control Nonmember', "
                + "CONCAT(UUID(), '@example.com'), 'routing-test-hash', 'UTC')");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO workspace_member "
                + "(workspace_id, user_id, role, status) VALUES (" + workspaceId + ", "
                + targetUserId + ", 'member', 'active')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".company "
                + "(id, workspace_id, name) VALUES (101, " + workspaceId + ", 'Scratch Company')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".person "
                + "(id, workspace_id, name) VALUES (201, " + workspaceId + ", 'Scratch Person')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".deal "
                + "(id, workspace_id, name) VALUES (301, " + workspaceId + ", 'Scratch Deal')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".tag "
                + "(id, workspace_id, name, color) VALUES (401, " + workspaceId
                + ", 'Routing Tag', '#123456')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".attachment "
                + "(id, workspace_id, entity_type, entity_id, file_name, url, content_type, size, "
                + "uploaded_by_id, created_at, updated_at) VALUES "
                + "(1, " + workspaceId + ", 'company', 101, 'company.pdf', "
                + "'/attachments/company.pdf', 'application/pdf', 10, " + uploaderId
                + ", '2026-07-04 00:00:00', '2026-07-04 00:00:00'), "
                + "(2, " + workspaceId + ", 'user', " + targetUserId + ", 'target.pdf', "
                + "'/attachments/control-target.pdf', 'application/pdf', 20, " + uploaderId
                + ", '2026-07-03 00:00:00', '2026-07-03 00:00:00'), "
                + "(3, " + workspaceId + ", 'deal', 301, 'deal.pdf', "
                + "'/attachments/deal.pdf', 'application/pdf', 30, NULL, "
                + "'2026-07-02 00:00:00', '2026-07-02 00:00:00'), "
                + "(4, " + workspaceId + ", 'user', " + nonmemberId + ", 'nonmember.pdf', "
                + "'/attachments/nonmember.pdf', 'application/pdf', 40, " + uploaderId + ", "
                + "'2026-07-01 00:00:00', '2026-07-01 00:00:00'), "
                + "(5, " + (workspaceId + 1) + ", 'company', 101, 'foreign.pdf', "
                + "'/attachments/foreign.pdf', 'application/pdf', 50, " + uploaderId
                + ", '2026-07-05 00:00:00', '2026-07-05 00:00:00')");
            statement.executeUpdate("INSERT INTO " + scratchCatalog + ".attachment_tag "
                + "(attachment_id, tag_id) VALUES (2, 401)");
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

    private static SqlSessionFactory sqlSessionFactory(TenantRoutingDataSource routing) throws Exception {
        Configuration configuration = new Configuration(
            new Environment("attachment-read-plane-routing", new JdbcTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/AttachmentMapper.xml", "mappers/TagMapper.xml", "mappers/UserMapper.xml")) {
            try (InputStream input = AttachmentReadPlaneRoutingIntegrationTest.class
                    .getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
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
            throw new IllegalStateException(
                "Could not inspect the routed connection catalog", exception);
        }
    }
}
