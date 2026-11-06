package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.RecordSearchMapper;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Kind;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Row;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordSearchReconciler;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Exercises actual sweeps, page commits, restart and cursor wrap across isolated organization catalogs. */
class RecordSearchReconcilerIntegrationTest {
    private final List<String> catalogs = new ArrayList<>();
    private String url;
    private String username;
    private String password;
    private String control;
    private String first;
    private String second;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private TenantContext context;
    private WorkspaceMapper workspaces;
    private RecordSearchMapper records;
    private TenantWorkScope scope;
    private DataSourceTransactionManager transactions;
    private RecordSearchReconciler reconciler;

    @BeforeEach
    void setUp() throws Exception {
        url = System.getenv("CONNEX_DB_URL");
        username = System.getenv("CONNEX_DB_USERNAME");
        password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "Database credentials required");
        String source = TenantRoutingConfig.databaseFromJdbcUrl(url);
        if (source == null) {
            throw new IllegalStateException("The test JDBC URL must name a catalog");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "");
        control = createCatalog("connexdb_routing_search_ctl_" + suffix);
        first = createCatalog("connexdb_routing_search_a_" + suffix);
        second = createCatalog("connexdb_routing_search_b_" + suffix);
        pool = new HikariDataSource();
        pool.setJdbcUrl(url.replace("/" + source, "/" + control));
        pool.setCatalog(control);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(3);
        pool.setConnectionTimeout(2000);
        jdbc = new JdbcTemplate(pool);
        jdbc.execute("CREATE TABLE " + control + ".organization (id INT PRIMARY KEY, name VARCHAR(255), slug VARCHAR(255), lifecycle_state VARCHAR(32) DEFAULT 'active')");
        jdbc.execute("CREATE TABLE " + control + ".workspace (id INT PRIMARY KEY, org_id INT NOT NULL, name VARCHAR(255), slug VARCHAR(255), timezone VARCHAR(64) DEFAULT 'Asia/Tokyo', identity_version BIGINT DEFAULT 1, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, lifecycle_state VARCHAR(32) DEFAULT 'active')");
        jdbc.update("INSERT INTO " + control + ".organization (id, name, slug) VALUES (1, 'Search A', 'a'), (2, 'Search B', 'b')");
        jdbc.update("INSERT INTO " + control + ".workspace (id, org_id, name, slug) VALUES (11, 1, 'Search A', 'a'), (22, 2, 'Search B', 'b')");
        for (String catalog : List.of(first, second)) {
            jdbc.execute("CREATE TABLE " + catalog + ".person (id INT PRIMARY KEY, workspace_id INT NOT NULL, name VARCHAR(255), phone VARCHAR(64), suspended_at DATETIME(6), provision_ceased_at DATETIME(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), INDEX idx_person_workspace(workspace_id))");
            jdbc.execute("CREATE TABLE " + catalog + ".company LIKE " + catalog + ".person");
            jdbc.execute("ALTER TABLE " + catalog + ".company RENAME INDEX idx_person_workspace TO idx_company_workspace");
            migration(catalog).migrate();
        }
        context = new TenantContext();
        var properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(control);
        var routing = TenantRoutingConfig.decorate(pool, properties, context);
        transactions = new DataSourceTransactionManager(routing);
        var configuration = new Configuration(new Environment("record-search", new SpringManagedTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(context, true));
        configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
        for (String resource : List.of("mappers/WorkspaceMapper.xml", "mappers/RecordSearchMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        workspaces = sessions.getMapper(WorkspaceMapper.class);
        records = spy(sessions.getMapper(RecordSearchMapper.class));
        var resolver = mock(TenantCatalogResolver.class);
        when(resolver.isCatalogPerPlacement()).thenReturn(true);
        when(resolver.resolveCatalog(1)).thenReturn(first);
        when(resolver.resolveCatalog(2)).thenReturn(second);
        var lifecycle = mock(TenantLifecycleControlMapper.class);
        scope = new TenantWorkScope(context, resolver, workspaces, lifecycle,
            new TenantWorkspaceScopeControlOperations(workspaces, lifecycle, resolver));
        reconciler = new RecordSearchReconciler(workspaces, scope, records, transactions);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (context != null) {
            context.clear();
        }
        if (pool != null) {
            pool.close();
        }
        if (url != null && username != null && password != null) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    var statement = connection.createStatement()) {
                for (String catalog : catalogs.reversed()) {
                    statement.execute("DROP DATABASE " + catalog);
                }
            }
        }
    }

    @Test
    void sweepCommitsBoundedPagesInEachCatalogAndDoesNotRetainEarlierLocks() {
        insertPeople(501, "090-9684-6208");
        insert(first, "person", 900, 22, "Foreign sentinel", null);
        insert(first, "company", 1, 11, "会社 Ａ", null);
        insert(second, "person", 1, 22, "別 会社", null);
        insert(second, "person", 2, 11, "Foreign sentinel", null);
        AtomicBoolean checkedCommit = new AtomicBoolean();
        doAnswer(invocation -> {
            if (invocation.<Kind>getArgument(1) == Kind.COMPANY && invocation.<Integer>getArgument(0) == 11
                    && checkedCommit.compareAndSet(false, true)) {
                assertEquals(500, filled(first, "person", 11));
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                        statement.executeUpdate("UPDATE " + first + ".person SET name = 'Concurrent edit' WHERE id = 1");
                    }
                    return null;
                });
            }
            return invocation.callRealMethod();
        }).when(records).maxId(anyInt(), any(Kind.class));
        reconciler.sweep();
        assertTrue(checkedCommit.get());
        assertEquals(499, filled(first, "person", 11));
        assertEquals(1, filled(first, "company", 11));
        assertEquals(1, filled(second, "person", 22));
        assertEquals(0, filled(first, "person", 22));
        assertEquals(0, filled(second, "person", 11));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN (?, ?) AND table_name = 'workspace'", Integer.class, first, second));
        runSweeps(4);
        assertEquals(501, filled(first, "person", 11));
    }

    @Test
    void failedPageRollsBackButCommittedDdlAndEarlierPagesSurviveNormalRestart() {
        insert(first, "person", 1, 11, "山田 太郎", null);
        insert(first, "company", 1, 11, "会社 Ａ", null);
        insert(first, "company", 2, 11, "会社 Ｂ", null);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (invocation.<Kind>getArgument(1) == Kind.COMPANY
                    && invocation.<Row>getArgument(2).id() == 2 && fail.getAndSet(false)) {
                throw new IllegalStateException("Injected page interruption");
            }
            return invocation.callRealMethod();
        }).when(records).reconcile(anyInt(), any(Kind.class), any(Row.class), any(String.class), any(String.class));
        reconciler.sweep();
        assertEquals(1, filled(first, "person", 11));
        assertEquals(0, filled(first, "company", 11));
        assertEquals(0, migration(first).migrate().migrationsExecuted);
        reconciler = new RecordSearchReconciler(workspaces, scope, records, transactions);
        reconciler.sweep();
        assertEquals(2, filled(first, "company", 11));
        assertNull(context.getCatalog());
    }

    @Test
    void cursorWrapRepairsCasConflictsLaterLegacyEditsAndInserts() {
        insert(first, "person", 1, 11, "山田 太郎", "090-9684-6208");
        AtomicBoolean edit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (invocation.<Kind>getArgument(1) == Kind.PERSON && edit.getAndSet(false)) {
                jdbc.update("UPDATE " + first + ".person SET name = '佐藤 花子' WHERE id = 1");
            }
            return invocation.callRealMethod();
        }).when(records).reconcile(anyInt(), any(Kind.class), any(Row.class), any(String.class), any(String.class));
        reconciler.sweep();
        assertEquals(0, filled(first, "person", 11));
        runSweeps(2);
        assertEquals("佐藤花子", key(first, 1));
        jdbc.update("UPDATE " + first + ".person SET name = '山田 太郎' WHERE id = 1");
        assertNull(key(first, 1));
        insert(first, "person", 2, 11, "新規 顧客", null);
        runSweeps(2);
        assertEquals("山田太郎", key(first, 1));
        assertEquals("新規顧客", key(first, 2));
        assertEquals("山田 太郎", jdbc.queryForObject("SELECT name FROM " + first + ".person WHERE id = 1", String.class));
    }

    @Test
    void rejectsAnAmbientTransactionBeforeRouting() {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            assertThrows(IllegalStateException.class, reconciler::sweep));
        assertFalse(context.isResolved());
    }

    @Test
    void completedRowsStillConsumeTheBoundedPageAndRestrictedRowsAreNotRebuilt() {
        insertPeople(501, null);
        jdbc.update("UPDATE " + first + ".person SET search_name = '山田太郎', search_phone = '' WHERE id <= 500");
        reconciler.sweep();
        assertNull(key(first, 501));
        reconciler.sweep();
        assertEquals("山田太郎", key(first, 501));
        jdbc.update("UPDATE " + first + ".person SET search_name = NULL, suspended_at = CURRENT_TIMESTAMP(6) WHERE id = 1");
        jdbc.update("UPDATE " + first + ".person SET search_name = NULL, provision_ceased_at = CURRENT_TIMESTAMP(6) WHERE id = 2");
        AtomicBoolean restrict = new AtomicBoolean(true);
        jdbc.update("UPDATE " + first + ".person SET search_name = NULL WHERE id = 3");
        doAnswer(invocation -> {
            if (invocation.<Row>getArgument(2).id() == 3 && restrict.getAndSet(false)) {
                jdbc.update("UPDATE " + first + ".person SET suspended_at = CURRENT_TIMESTAMP(6) WHERE id = 3");
            }
            return invocation.callRealMethod();
        }).when(records).reconcile(anyInt(), any(Kind.class), any(Row.class), any(String.class), any(String.class));
        reconciler.sweep();
        assertNull(key(first, 1));
        assertNull(key(first, 2));
        assertNull(key(first, 3));
        assertEquals(498, filled(first, "person", 11));
    }

    @Test
    void nonUtf8ConnectionsRepairJapaneseSourcesWithoutLossyCas() throws Exception {
        String name = "株式会社 𠀀 Ｃｐ";
        String phone = "０９０・９６８４・６２０８";
        insert(first, "person", 1, 11, name, phone);
        insert(second, "company", 1, 22, name, phone);
        try (var cpPool = new HikariDataSource()) {
            String cpUrl = pool.getJdbcUrl().replaceAll("(?i)(characterEncoding|connectionCollation)=[^&]*&?", "");
            cpUrl += (cpUrl.contains("?") ? "&" : "?")
                + "characterEncoding=Cp1252&connectionCollation=latin1_bin";
            cpPool.setJdbcUrl(cpUrl);
            cpPool.setCatalog(control);
            cpPool.setUsername(username);
            cpPool.setPassword(password);
            cpPool.setMaximumPoolSize(2);
            var properties = new TenantRoutingProperties();
            properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
            properties.setDefaultCatalog(control);
            var routing = TenantRoutingConfig.decorate(cpPool, properties, context);
            var configuration = new Configuration(new Environment("record-search-cp1252",
                new SpringManagedTransactionFactory(), routing));
            configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
            String resource = "mappers/RecordSearchMapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
            var cpRecords = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(RecordSearchMapper.class);
            assertEquals("latin1", new JdbcTemplate(cpPool).queryForObject("SELECT @@character_set_client", String.class));
            new RecordSearchReconciler(workspaces, scope, cpRecords, new DataSourceTransactionManager(routing)).sweep();
        }
        assertEquals("株式会社𠀀cp", key(first, 1));
        assertEquals("819096846208", jdbc.queryForObject("SELECT search_phone FROM " + first + ".person WHERE id = 1", String.class));
        assertEquals("株式会社𠀀cp", jdbc.queryForObject("SELECT search_name FROM " + second + ".company WHERE id = 1", String.class));
        assertEquals(name, jdbc.queryForObject("SELECT name FROM " + first + ".person WHERE id = 1", String.class));
    }

    private void runSweeps(int count) {
        for (int index = 0; index < count; index++) {
            reconciler.sweep();
        }
    }

    private int filled(String catalog, String table, int workspaceId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + catalog + "." + table
            + " WHERE workspace_id = ? AND search_name IS NOT NULL AND search_phone IS NOT NULL", Integer.class, workspaceId);
        if (count == null) {
            throw new IllegalStateException("Missing count");
        }
        return count;
    }

    private String key(String catalog, int id) {
        return jdbc.queryForObject("SELECT search_name FROM " + catalog + ".person WHERE id = ?", String.class, id);
    }

    private void insert(String catalog, String table, int id, int workspaceId, String name, String phone) {
        jdbc.update("INSERT INTO " + catalog + "." + table + " (id, workspace_id, name, phone) VALUES (?, ?, ?, ?)", id, workspaceId, name, phone);
    }

    private void insertPeople(int count, String phone) {
        new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status -> {
            for (int id = 1; id <= count; id++) {
                insert(first, "person", id, 11, "山田 太郎", phone);
            }
        });
    }

    private String createCatalog(String catalog) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + catalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        catalogs.add(catalog);
        return catalog;
    }

    private Flyway migration(String catalog) {
        return Flyway.configure().dataSource(url, username, password).defaultSchema(catalog).schemas(catalog)
            .locations("classpath:db/migration/tenant").baselineOnMigrate(true).baselineVersion("243")
            .target("244").load();
    }
}
