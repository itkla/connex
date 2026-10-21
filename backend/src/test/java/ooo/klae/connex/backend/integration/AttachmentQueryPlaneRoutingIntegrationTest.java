package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AttachmentQueryControlAccess;
import ooo.klae.connex.backend.services.AttachmentQueryReadService;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.support.LegacyAttachmentSql;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.util.LikePattern;

/** Proves frozen parity, same-connection snapshots and the routed refusal against minimal catalogs. */
class AttachmentQueryPlaneRoutingIntegrationTest {
    private Fixture fixture;

    @AfterEach
    void closeFixture() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void allFourStatementsMatchFrozenSqlInSingleAndSplitCatalogs() throws Exception {
        for (boolean split : List.of(false, true)) {
            fixture = new Fixture(split);
            if (split) {
                for (String table : TablePlaneRegistry.CONTROL_PLANE_TABLES) {
                    assertEquals(0, fixture.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                        Integer.class, fixture.tenantCatalog, table), table);
                }
                assertEquals(Fixture.TABLES.stream().sorted().toList(), fixture.jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
                    String.class, fixture.tenantCatalog));
            }
            var cases = fixture.cases();
            List<List<?>> expected = new ArrayList<>();
            for (ReadCase read : cases) {
                expected.add(canonical(fixture.legacy.selectList(read.statement(), read.parameters())));
            }
            if (split) {
                fixture.jdbc.update("UPDATE company SET name = 'Default Catalog Decoy' WHERE id = ?", fixture.companyId);
            }
            for (int index = 0; index < cases.size(); index++) {
                ReadCase read = cases.get(index);
                var parameters = new HashMap<>(read.parameters());
                var ids = fixture.attachments.getQueryUserIds(fixture.workspaceId, fixture.viewerId, true, true);
                parameters.put("userFactsJson", fixture.control.userFactsJson(ids, (String) parameters.get("query")));
                List<?> actual = fixture.sessions.selectList(
                    "ooo.klae.connex.backend.mappers.AttachmentMapper." + read.statement(), parameters);
                assertEquals(expected.get(index), canonical(actual), read.toString());
            }
            if (split) {
                int checkouts = fixture.connections.checkouts.get();
                int facts = fixture.factReads.get();
                assertThrows(TenantScopeSnapshotException.class,
                    () -> fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%file%", "oldest",
                        null, null, null, null, 2, 1));
                assertEquals(checkouts, fixture.connections.checkouts.get());
                assertEquals(facts, fixture.factReads.get());
            } else {
                var page = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%file%", "oldest",
                    null, null, null, null, 2, 1);
                assertEquals(2, page.items().size());
                assertEquals(fixture.visibleTotal, page.total());
            }
            assertEquals(split ? fixture.tenantCatalog : fixture.defaultCatalog, fixture.currentCatalog());
            if (split) {
                assertThrows(IllegalStateException.class, () -> new LegacyAttachmentSql(
                    fixture.connections, fixture.defaultCatalog).selectList("countOrphaned", fixture.parameters(null, null, null)));
            }
            fixture.close();
            fixture = null;
        }
    }

    @Test
    void forgedForeignUserAndDepartedTargetsKeepTheirLegacyNamesSearchAndOrphanStatus() throws Exception {
        fixture = new Fixture(false);
        var foreign = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Foreign%", "oldest",
            List.of("user"), null, null, null, 100, 0);
        assertEquals(1, foreign.total());
        assertEquals("Foreign Émile %_", foreign.items().getFirst().getEntityLabel());
        var departed = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Departed%", null,
            List.of("user"), null, null, null, 100, 0);
        assertEquals(1, departed.total());
        assertEquals("Departed Target", departed.items().getFirst().getEntityLabel());
        assertEquals(0, fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Foreign%", null,
            List.of("user"), null, null, true, 100, 0).total());
        assertEquals(0, fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Departed%", null,
            List.of("user"), null, null, true, 100, 0).total());
        assertEquals(0, fixture.jdbc.queryForObject(
            "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id IN (?, ?)", Integer.class,
            fixture.workspaceId, fixture.foreignUserId, fixture.departedId));
    }

    @Test
    void activePendingAndEmptyTargetNamesKeepTheirLegacyClassification() throws Exception {
        fixture = new Fixture(false);
        var pending = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Pending%", null,
            List.of("user"), null, null, null, 100, 0);
        assertEquals(1, pending.total());
        assertEquals("Pending Target", pending.items().getFirst().getEntityLabel());
        var active = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, "%Library Viewer%", null,
            List.of("user"), null, null, null, 100, 0);
        assertEquals(1, active.total());
        assertEquals("Library Viewer", active.items().getFirst().getEntityLabel());
        var all = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, null, null,
            List.of("user"), null, null, null, 100, 0);
        assertTrue(all.items().stream().anyMatch(a -> a.getEntityId() == fixture.emptyNameId
            && "".equals(a.getEntityLabel())));
        var orphaned = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, null, null,
            List.of("user"), null, null, true, 100, 0);
        assertEquals(1, orphaned.total());
        assertEquals(-1, orphaned.items().getFirst().getEntityId());
    }

    @Test
    void connectionCollationCannotChangeSourceNameMatchingOrProjectedNames() throws Exception {
        fixture = new Fixture(false, "utf8mb4_general_ci");
        assertEquals("utf8mb4_general_ci", fixture.jdbc.queryForObject("SELECT @@collation_connection", String.class));
        for (ReadCase read : fixture.cases()) {
            var parameters = new HashMap<>(read.parameters());
            var ids = fixture.attachments.getQueryUserIds(fixture.workspaceId, fixture.viewerId, true, true);
            parameters.put("userFactsJson", fixture.control.userFactsJson(ids, (String) parameters.get("query")));
            assertEquals(canonical(fixture.legacy.selectList(read.statement(), read.parameters())),
                canonical(fixture.sessions.selectList(
                    "ooo.klae.connex.backend.mappers.AttachmentMapper." + read.statement(), parameters)), read.toString());
        }
    }

    @Test
    void concurrentRenameAndInsertAfterDiscoveryRemainOutsideTheEntireOperationSnapshot() throws Exception {
        fixture = new Fixture(false);
        fixture.beforeFacts = () -> {
            try (Connection other = DriverManager.getConnection(fixture.url, fixture.username, fixture.password)) {
                try (var rename = other.prepareStatement("UPDATE app_user SET display_name = ? WHERE id = ?")) {
                    rename.setString(1, "Concurrent Rename");
                    rename.setInt(2, fixture.foreignUserId);
                    assertEquals(1, rename.executeUpdate());
                }
                try (var insert = other.prepareStatement("INSERT INTO attachment (workspace_id, entity_type, entity_id, "
                        + "file_name, url, content_type, size, uploaded_by_id) VALUES (?, 'user', ?, 'concurrent-file.pdf', ?, "
                        + "'application/pdf', 1, ?)")) {
                    insert.setInt(1, fixture.workspaceId);
                    insert.setInt(2, fixture.foreignUserId);
                    insert.setString(3, "/attachments/concurrent-" + UUID.randomUUID());
                    insert.setInt(4, fixture.uploaderId);
                    assertEquals(1, insert.executeUpdate());
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        };
        int before = fixture.connections.checkouts.get();
        var page = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, null, "oldest",
            null, null, null, null, 100, 0);
        assertEquals(before + 1, fixture.connections.checkouts.get());
        assertEquals(fixture.visibleTotal, page.total());
        assertEquals(fixture.visibleTotal, page.items().size());
        assertTrue(page.items().stream().anyMatch(a -> "Foreign Émile %_".equals(a.getEntityLabel())));
        assertFalse(page.items().stream().anyMatch(a -> "concurrent-file.pdf".equals(a.getFileName())));
        var next = fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, null, "oldest",
            null, null, null, null, 100, 0);
        assertEquals(fixture.visibleTotal + 1, next.total());
        assertTrue(next.items().stream().anyMatch(a -> "Concurrent Rename".equals(a.getEntityLabel())));
        assertTrue(next.items().stream().anyMatch(a -> "concurrent-file.pdf".equals(a.getFileName())));
    }

    @Test
    void routedTransactionRefusesWithoutAnotherCheckoutOrControlRead() throws Exception {
        fixture = new Fixture(true);
        new TransactionTemplate(fixture.transactions).executeWithoutResult(status -> {
            var connection = DataSourceUtils.getConnection(fixture.connections);
            int checkouts = fixture.connections.checkouts.get();
            int facts = fixture.factReads.get();
            assertThrows(TenantScopeSnapshotException.class, () -> fixture.reads.search(fixture.workspaceId, "%file%", fixture.viewerId));
            assertThrows(TenantScopeSnapshotException.class,
                () -> fixture.reads.getPage(fixture.workspaceId, fixture.viewerId, null, null, null, null, null, null, 10, 0));
            assertThrows(TenantScopeSnapshotException.class, () -> fixture.reads.facets(fixture.workspaceId, fixture.viewerId));
            assertThrows(TenantScopeSnapshotException.class,
                () -> fixture.control.userFactsJson(List.of(fixture.uploaderId), null));
            assertEquals(checkouts, fixture.connections.checkouts.get());
            assertEquals(facts, fixture.factReads.get());
            assertSame(connection, DataSourceUtils.getConnection(fixture.connections));
        });
        assertEquals(fixture.tenantCatalog, fixture.currentCatalog());
    }

    private static List<?> canonical(List<?> rows) {
        return rows.stream().map(row -> row instanceof Attachment attachment ? AttachmentDto.from(attachment) : row).toList();
    }

    private record ReadCase(String statement, Map<String, Object> parameters) { }

    private static final class CountingConnections extends DelegatingDataSource {
        final AtomicInteger checkouts = new AtomicInteger();
        CountingConnections(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException {
            checkouts.incrementAndGet();
            return super.getConnection();
        }
    }

    private static final class Fixture implements AutoCloseable {
        static final List<String> TABLES = List.of("attachment", "attachment_tag", "tag", "note", "company", "person", "deal");
        final String url = System.getenv("CONNEX_DB_URL");
        final String username = System.getenv("CONNEX_DB_USERNAME");
        final String password = System.getenv("CONNEX_DB_PASSWORD");
        final String defaultCatalog;
        final String tenantCatalog;
        final HikariDataSource pool;
        final JdbcTemplate jdbc;
        final TenantContext context = new TenantContext();
        final List<Integer> workspaces = new ArrayList<>();
        final List<Integer> organizations = new ArrayList<>();
        final List<Integer> users = new ArrayList<>();
        final CountingConnections connections;
        final DataSourceTransactionManager transactions;
        final SqlSessionTemplate sessions;
        final AttachmentMapper attachments;
        final AttachmentQueryControlAccess control;
        final AttachmentQueryReadService reads;
        final LegacyAttachmentSql legacy;
        final AtomicInteger factReads = new AtomicInteger();
        Runnable beforeFacts;
        int workspaceId;
        int orgId;
        int siblingId;
        int foreignWorkspaceId;
        int uploaderId;
        int viewerId;
        int foreignUserId;
        int departedId;
        int emptyNameId;
        int companyId;
        int tagId;
        int sequence;
        int visibleTotal;
        boolean scratchCreated;
        boolean closed;

        Fixture(boolean split) throws Exception {
            this(split, null);
        }

        Fixture(boolean split, String connectionCollation) throws Exception {
            if (url == null || username == null || password == null) {
                throw new IllegalStateException("Database credentials required for attachment plane tests");
            }
            defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
            tenantCatalog = split ? "cx_att_query_" + UUID.randomUUID().toString().replace("-", "") : defaultCatalog;
            pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setUsername(username);
            pool.setPassword(password);
            if (connectionCollation != null) {
                pool.setConnectionInitSql("SET NAMES utf8mb4 COLLATE " + connectionCollation);
            }
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(2_000);
            Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .callbacks(new AuditLogV126MigrationCallback()).baselineOnMigrate(true).baselineVersion("0").load().migrate();
            jdbc = new JdbcTemplate(pool);
            seed();
            if (split) {
                jdbc.execute("CREATE DATABASE " + tenantCatalog);
                scratchCreated = true;
                for (String table : TABLES) {
                    jdbc.execute("CREATE TABLE " + tenantCatalog + "." + table + " LIKE " + defaultCatalog + "." + table);
                    if (table.equals("attachment_tag")) {
                        jdbc.update("INSERT INTO " + tenantCatalog + ".attachment_tag SELECT j.* FROM attachment_tag j "
                            + "JOIN attachment a ON a.id = j.attachment_id WHERE a.workspace_id IN (?, ?, ?)",
                            workspaceId, siblingId, foreignWorkspaceId);
                    } else {
                        jdbc.update("INSERT INTO " + tenantCatalog + "." + table + " SELECT * FROM " + table
                            + " WHERE workspace_id IN (?, ?, ?)", workspaceId, siblingId, foreignWorkspaceId);
                    }
                }
            }
            var properties = new TenantRoutingProperties();
            properties.setMode(split ? TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT : TenantRoutingProperties.MODE_SINGLE_DATABASE);
            properties.setDefaultCatalog(defaultCatalog);
            connections = new CountingConnections(TenantRoutingConfig.decorate(pool, properties, context));
            transactions = new DataSourceTransactionManager(connections);
            Configuration configuration = new Configuration(new Environment(
                "attachment-query-plane", new SpringManagedTransactionFactory(), connections));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
            configuration.addInterceptor(new ControlCatalogRoutingInterceptor(context, split));
            configuration.addInterceptor(new TenantScopeInterceptor(context, true, split));
            for (String resource : List.of("AttachmentMapper", "UserMapper", "TagMapper")) {
                String path = "mappers/" + resource + ".xml";
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                    new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
                }
            }
            sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
            attachments = sessions.getMapper(AttachmentMapper.class);
            var catalogs = mock(TenantCatalogResolver.class);
            when(catalogs.isCatalogPerPlacement()).thenReturn(split);
            var scope = new TenantWorkScope(context, catalogs, mock(WorkspaceMapper.class),
                mock(TenantLifecycleControlMapper.class), mock(TenantWorkspaceScopeControlOperations.class));
            UserMapper delegate = sessions.getMapper(UserMapper.class);
            UserMapper countedUsers = (UserMapper) Proxy.newProxyInstance(UserMapper.class.getClassLoader(),
                new Class<?>[] { UserMapper.class }, (proxy, method, args) -> {
                    if (method.getName().equals("getAttachmentQueryFactsByIds")) {
                        factReads.incrementAndGet();
                        Runnable hook = beforeFacts;
                        beforeFacts = null;
                        if (hook != null) { hook.run(); }
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            control = new AttachmentQueryControlAccess(countedUsers, scope, JsonMapper.builder().build());
            reads = new AttachmentQueryReadService(attachments, control, scope, transactions);
            legacy = new LegacyAttachmentSql(pool, defaultCatalog);
            context.set(workspaceId, orgId, viewerId, "member", split ? tenantCatalog : null);
        }

        Map<String, Object> parameters(String query, String sort, Boolean orphaned) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("workspaceId", workspaceId);
            parameters.put("currentUserId", viewerId);
            parameters.put("query", query);
            parameters.put("sort", sort);
            parameters.put("types", null);
            parameters.put("kinds", null);
            parameters.put("tagIds", null);
            parameters.put("orphaned", orphaned);
            parameters.put("limit", 2);
            parameters.put("offset", 1);
            return parameters;
        }

        List<ReadCase> cases() {
            var cases = new ArrayList<ReadCase>();
            for (String query : Arrays.asList(null, "%file%", "%Uploader%", "%Foreign%", "%Departed%",
                    "%emile%", "%ÉMILE%", "%FOREIGN%", "%Pending%", "%%", LikePattern.containing("%_"))) {
                cases.add(new ReadCase("search", parameters(query, null, null)));
                for (String sort : List.of("newest", "oldest", "name", "largest")) {
                    for (Boolean orphaned : Arrays.asList(null, false, true)) {
                        cases.add(new ReadCase("getPage", parameters(query, sort, orphaned)));
                        cases.add(new ReadCase("countPage", parameters(query, sort, orphaned)));
                    }
                }
            }
            for (String key : List.of("types", "kinds", "tagIds")) {
                Map<String, Object> filtered = parameters("%file%", "oldest", null);
                filtered.put(key, key.equals("types") ? List.of("company", "user") : key.equals("kinds") ? List.of("pdf") : List.of(tagId));
                cases.add(new ReadCase("getPage", filtered));
                cases.add(new ReadCase("countPage", filtered));
            }
            for (int offset : List.of(0, 100)) {
                var bounds = parameters("%file%", "oldest", null);
                bounds.put("offset", offset);
                bounds.put("limit", 100);
                cases.add(new ReadCase("getPage", bounds));
                cases.add(new ReadCase("countPage", bounds));
            }
            cases.add(new ReadCase("countOrphaned", parameters(null, null, null)));
            return cases;
        }

        void seed() {
            orgId = organization();
            workspaceId = workspace(orgId);
            siblingId = workspace(orgId);
            foreignWorkspaceId = workspace(organization());
            uploaderId = user("Historical Uploader");
            viewerId = user("Library Viewer");
            foreignUserId = user("Foreign Émile %_");
            departedId = user("Departed Target");
            int pendingId = user("Pending Target");
            emptyNameId = user("");
            jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES (?, ?, 'member', 'active')", workspaceId, viewerId);
            jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES (?, ?, 'member', 'pending')", workspaceId, pendingId);
            jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES (?, ?, 'member', 'active')", workspaceId, departedId);
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?", workspaceId, departedId);
            jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role, status) VALUES (?, ?, 'member', 'active')", foreignWorkspaceId, foreignUserId);
            companyId = insert("INSERT INTO company (workspace_id, name) VALUES (?, 'Owned Company')", workspaceId);
            int shared = insert("INSERT INTO company (workspace_id, name) VALUES (?, 'Shared Company')", siblingId);
            int unshared = insert("INSERT INTO company (workspace_id, name) VALUES (?, 'Unshared Company')", siblingId);
            int forged = insert("INSERT INTO company (workspace_id, name) VALUES (?, 'Foreign Company')", foreignWorkspaceId);
            for (int id : List.of(shared, forged)) {
                jdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by) VALUES (?, ?, ?)", id, workspaceId, viewerId);
            }
            int archived = insert("INSERT INTO company (workspace_id, name, archived_at) VALUES (?, 'Archived Company', CURRENT_TIMESTAMP)", workspaceId);
            int person = insert("INSERT INTO person (workspace_id, name) VALUES (?, 'Owned Person')", workspaceId);
            int workspaceNote = insert("INSERT INTO note (workspace_id, content, visibility, author_id) VALUES (?, 'Shared note', 'workspace', ?)", workspaceId, uploaderId);
            int privateNote = insert("INSERT INTO note (workspace_id, content, visibility, author_id) VALUES (?, 'Private note', 'private', ?)", workspaceId, uploaderId);
            tagId = insert("INSERT INTO tag (workspace_id, name, color) VALUES (?, ?, '#aaaaaa')", workspaceId, UUID.randomUUID().toString());
            for (int id : List.of(companyId, shared, unshared, forged, archived)) {
                attachment(workspaceId, "company", id, uploaderId);
            }
            attachment(workspaceId, "person", person, uploaderId);
            attachment(workspaceId, "user", foreignUserId, uploaderId);
            attachment(workspaceId, "USER", departedId, uploaderId);
            attachment(workspaceId, "user", viewerId, uploaderId);
            attachment(workspaceId, "user", pendingId, uploaderId);
            attachment(workspaceId, "user", emptyNameId, uploaderId);
            attachment(workspaceId, "user", -1, null);
            attachment(workspaceId, "note", workspaceNote, uploaderId);
            for (int index = 0; index < 12; index++) { attachment(workspaceId, "company", companyId, uploaderId); }
            visibleTotal = sequence;
            attachment(workspaceId, "note", privateNote, uploaderId);
            attachment(workspaceId, "note", -1, uploaderId);
            attachment(workspaceId, "ai_chat_session", -1, uploaderId);
            attachment(siblingId, "company", companyId, uploaderId);
            attachment(foreignWorkspaceId, "company", companyId, uploaderId);
        }

        void attachment(int workspace, String type, int entity, Integer uploader) {
            sequence++;
            int id = insert("INSERT INTO attachment (workspace_id, entity_type, entity_id, file_name, url, content_type, size, "
                + "uploaded_by_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'application/pdf', ?, ?, ?, ?)",
                workspace, type, entity, String.format("file_%02d.pdf", sequence), "/attachments/" + UUID.randomUUID(),
                sequence, uploader, String.format("2026-10-01 00:00:%02d", sequence), String.format("2026-10-01 00:00:%02d", sequence));
            if (workspace == workspaceId) {
                jdbc.update("INSERT INTO attachment_tag (attachment_id, tag_id) VALUES (?, ?)", id, tagId);
            }
        }

        int organization() {
            int id = insert("INSERT INTO organization (name, slug) VALUES ('Attachment Query Org', ?)", UUID.randomUUID().toString());
            organizations.add(id);
            return id;
        }

        int workspace(int organization) {
            int id = insert("INSERT INTO workspace (org_id, name, slug) VALUES (?, 'Attachment Query Workspace', ?)", organization, UUID.randomUUID().toString());
            workspaces.add(id);
            return id;
        }

        int user(String name) {
            String unique = UUID.randomUUID().toString();
            int id = insert("INSERT INTO app_user (username, display_name, email, password_hash, timezone) VALUES (?, ?, ?, 'fixture', 'UTC')",
                unique, name, unique + "@example.com");
            users.add(id);
            return id;
        }

        int insert(String sql, Object... args) {
            var keys = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int index = 0; index < args.length; index++) { statement.setObject(index + 1, args[index]); }
                return statement;
            }, keys);
            return keys.getKey().intValue();
        }

        String currentCatalog() throws SQLException {
            var connection = DataSourceUtils.getConnection(connections);
            try { return connection.getCatalog(); }
            finally { DataSourceUtils.releaseConnection(connection, connections); }
        }

        @Override public void close() {
            if (closed) { return; }
            closed = true;
            context.clear();
            try {
                if (scratchCreated) { jdbc.execute("DROP DATABASE " + tenantCatalog); }
                for (int workspace : workspaces) {
                    jdbc.update("DELETE j FROM attachment_tag j JOIN attachment a ON a.id = j.attachment_id WHERE a.workspace_id = ?", workspace);
                    jdbc.update("DELETE FROM attachment WHERE workspace_id = ?", workspace);
                    jdbc.update("DELETE FROM note WHERE workspace_id = ?", workspace);
                    jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspace);
                    jdbc.update("DELETE s FROM company_share s JOIN company c ON c.id = s.company_id WHERE c.workspace_id = ? OR s.workspace_id = ?", workspace, workspace);
                    jdbc.update("DELETE FROM company WHERE workspace_id = ?", workspace);
                    jdbc.update("DELETE FROM tag WHERE workspace_id = ?", workspace);
                    jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace);
                }
                for (int workspace : workspaces) { jdbc.update("DELETE FROM workspace WHERE id = ?", workspace); }
                for (int organization : organizations) { jdbc.update("DELETE FROM organization WHERE id = ?", organization); }
                for (int user : users) { jdbc.update("DELETE FROM app_user WHERE id = ?", user); }
            } finally { pool.close(); }
        }
    }
}
