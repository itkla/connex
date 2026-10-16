package ooo.klae.connex.backend.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.mappers.AiAssistantIdentifierMapper;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AiIdentifierKeyBackfillRunner;
import ooo.klae.connex.backend.services.AiIdentifierKeyBackfillTransaction;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Real routed scheduler-thread sweeps and committed lock races, without mocked placement or mappers. */
class AiIdentifierKeyBackfillConcurrencyIntegrationTest {
    private MySqlScratchCatalog controlCatalog;
    private MySqlScratchCatalog tenantCatalog;
    private JdbcTemplate control;
    private JdbcTemplate tenant;
    private TenantContext context;
    private TenantWorkScope scope;
    private AiIdentifierKeyBackfillTransaction backfill;
    private AiIdentifierKeyBackfillRunner worker;
    private CasProbe probe;

    @BeforeEach
    void routedCatalogs() throws Exception {
        String url = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        assertNotNull(url, "Database execution required");
        assertNotNull(username);
        assertNotNull(password);
        controlCatalog = MySqlScratchCatalog.create(url, username, password, "identifier_control_", "utf8mb4", "utf8mb4_bin");
        tenantCatalog = MySqlScratchCatalog.create(url, username, password, "cnx_identifier_", "utf8mb4", "utf8mb4_bin");
        try (Connection source = DriverManager.getConnection(url, username, password)) {
            cloneTables(source.getCatalog(), controlCatalog, List.of("organization", "workspace", "org_placement", "person", "company", "deal"));
            cloneTables(source.getCatalog(), tenantCatalog, List.of("person", "company", "deal"));
        }
        DataSource pool = dataSource(controlCatalog.url());
        control = new JdbcTemplate(pool);
        tenant = new JdbcTemplate(dataSource(tenantCatalog.url()));
        control.update("INSERT INTO organization (id, name, slug) VALUES (1, 'Identifier fixture', 'identifier-fixture')");
        control.update("INSERT INTO workspace (id, org_id, name, slug) VALUES (1, 1, 'Identifier fixture', 'identifier-fixture')");
        control.update("INSERT INTO org_placement (org_id, placement_mode, database_handle, storage_encryption_mode, key_controller) VALUES (1, 'dedicated_database', ?, 'provider_managed', 'connex_cloud_provider')", tenantCatalog.name());
        context = new TenantContext();
        var routing = new TenantRoutingDataSource(pool, context, controlCatalog.name(), connection -> { });
        var configuration = new Configuration(new Environment("identifier-worker-routing", new SpringManagedTransactionFactory(), routing));
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(context, true));
        configuration.addInterceptor(new TenantScopeInterceptor(context, true, true));
        probe = new CasProbe();
        configuration.addInterceptor(probe);
        for (String resource : List.of("mappers/WorkspaceMapper.xml", "mappers/OrgPlacementMapper.xml", "mappers/AiAssistantIdentifierMapper.xml")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        var sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        WorkspaceMapper workspaces = sessions.getMapper(WorkspaceMapper.class);
        var properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(controlCatalog.name());
        var placements = new PlacementRegistry(sessions.getMapper(OrgPlacementMapper.class), properties);
        scope = new TenantWorkScope(context, new TenantCatalogResolver(placements, properties), workspaces);
        var target = new AiIdentifierKeyBackfillTransaction(sessions.getMapper(AiAssistantIdentifierMapper.class));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(routing), new AnnotationTransactionAttributeSource()));
        backfill = (AiIdentifierKeyBackfillTransaction) proxy.getProxy();
        worker = new AiIdentifierKeyBackfillRunner(placements, scope, workspaces, backfill);
    }

    @AfterEach
    void dropCatalogs() throws Exception {
        if (tenantCatalog != null) {
            tenantCatalog.close();
        }
        if (controlCatalog != null) {
            controlCatalog.close();
        }
    }

    @Test
    void backgroundThreadPinsRealPlacementBeforeEachTransactionAndResumesBoundedPages() throws Exception {
        for (int id = 1; id <= 405; id++) {
            tenant.update("INSERT INTO person (id, workspace_id, name) VALUES (?, 1, ?)", id, "名前" + id);
        }
        control.update("INSERT INTO person (id, workspace_id, name) VALUES (1, 1, 'Decoy 名前')");
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertFalse(context.isResolved());
                assertEquals(null, RequestContextHolder.getRequestAttributes());
                var refused = assertThrows(RuntimeException.class, () -> backfill.backfillPage(1, "person", 0));
                Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(refused);
                assertTrue(cause instanceof IllegalStateException);
                assertTrue(cause.getMessage().contains("no catalog scope"));
                worker.reconcile();
                assertFalse(context.isResolved());
                assertEquals(null, context.getCatalog());
            }).get(60, TimeUnit.SECONDS);
            assertEquals(400, keyed(tenant));
            assertEquals(0, keyed(control));
            executor.submit(worker::reconcile).get(60, TimeUnit.SECONDS);
            assertEquals(405, keyed(tenant));
            assertEquals(0, keyed(control));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void compareAndSetRechecksCommittedRenameOrRestrictionAfterARealLockWait(boolean restriction) throws Exception {
        tenant.update("INSERT INTO person (id, workspace_id, name) VALUES (1, 1, 'Original 名前')");
        probe.started = new CountDownLatch(1);
        try (Connection holder = dataSource(tenantCatalog.url()).getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var statement = holder.createStatement()) {
                    statement.executeUpdate(restriction
                            ? "UPDATE person SET suspended_at = CURRENT_TIMESTAMP WHERE id = 1"
                            : "UPDATE person SET name = 'Renamed 名前' WHERE id = 1");
                }
                var repairing = executor.submit(() -> scope.inWorkspace(1, () -> backfill.backfillPage(1, "person", 0)));
                assertTrue(probe.started.await(30, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(new JdbcTemplate(new SingleConnectionDataSource(holder, true)),
                        probe.connectionId.get(), "person", "1");
                holder.commit();
                var result = repairing.get(30, TimeUnit.SECONDS);
                assertEquals(1, result.scanned());
                assertEquals(0, result.written());
                assertEquals(1, result.skipped());
                assertEquals(0, keyed(tenant));
                if (!restriction) {
                    assertEquals(1, scope.inWorkspace(1, () -> backfill.backfillPage(1, "person", 0)).written());
                    assertEquals("Renamed 名前", tenant.queryForObject("SELECT ai_identifier_source_name FROM person WHERE id = 1", String.class));
                }
            } finally {
                holder.rollback();
            }
        }
    }

    @Test
    void eachCommittedPageReleasesItsLocksBeforeTheNextPage() throws Exception {
        for (int id = 1; id <= 101; id++) {
            tenant.update("INSERT INTO person (id, workspace_id, name) VALUES (?, 1, ?)", id, "名前" + id);
        }
        var first = scope.inWorkspace(1, () -> backfill.backfillPage(1, "person", 0));
        assertEquals(100, first.written());
        try (Connection other = dataSource(tenantCatalog.url()).getConnection(); var statement = other.createStatement()) {
            other.setAutoCommit(false);
            try (var rows = statement.executeQuery("SELECT id FROM person WHERE id = 1 FOR UPDATE NOWAIT")) {
                assertTrue(rows.next());
            }
            statement.executeUpdate("UPDATE person SET name = 'Between pages 名前' WHERE id = 1");
            other.commit();
        }
        assertEquals(1, scope.inWorkspace(1, () -> backfill.backfillPage(1, "person", first.lastId())).written());
        assertEquals("名前1", tenant.queryForObject("SELECT ai_identifier_source_name FROM person WHERE id = 1", String.class));
        assertEquals(1, scope.inWorkspace(1, () -> backfill.backfillPage(1, "person", 0)).written());
        assertEquals("Between pages 名前", tenant.queryForObject("SELECT ai_identifier_source_name FROM person WHERE id = 1", String.class));
    }

    private int keyed(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM person WHERE ai_identifier_key_version IS NOT NULL", Integer.class);
    }

    private DataSource dataSource(String url) {
        return new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, System.getenv("CONNEX_DB_USERNAME"), System.getenv("CONNEX_DB_PASSWORD"));
    }

    private void cloneTables(String source, MySqlScratchCatalog catalog, List<String> tables) throws Exception {
        assertTrue(source.matches("[A-Za-z0-9_]+"));
        try (Connection connection = dataSource(catalog.url()).getConnection(); var statement = connection.createStatement()) {
            for (String table : tables) {
                statement.execute("CREATE TABLE " + table + " LIKE `" + source + "`." + table);
            }
            try (var columns = connection.getMetaData().getColumns(catalog.name(), null, "person", "ai_identifier_literal_key")) {
                if (!columns.next()) {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/tenant/V230__ai_identifier_lookup_keys.sql"));
                }
            }
        }
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    public static class CasProbe implements Interceptor {
        private final AtomicLong connectionId = new AtomicLong();
        private volatile CountDownLatch started;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (started != null && statement.getId().endsWith(".updatePersonKeysIfNameCurrent")) {
                try (var query = ((Executor) invocation.getTarget()).getTransaction().getConnection().createStatement();
                        var rows = query.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(rows.next());
                    connectionId.set(rows.getLong(1));
                }
                started.countDown();
            }
            return invocation.proceed();
        }
    }
}
