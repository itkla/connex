package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Minimal migrated-default fixtures and eight-table tenant clones for restriction cache erasure. */
public final class AiOutputCachePlaneFixture implements AutoCloseable {
    public static final List<String> TENANT_TABLES = List.of(
        "ai_output_cache", "deal_person", "deal", "person_edge", "activity", "note", "task", "person");
    public final TenantContext tenantContext = new TenantContext();
    public final HikariDataSource pool;
    public final TenantRoutingDataSource routing;
    public final CountingDataSource connections;
    public final DataSourceTransactionManager transactions;
    public final JdbcTemplate control;
    public final JdbcTemplate tenant;
    public final SqlSessionTemplate sessions;
    public final AiOutputCacheMapper cache;
    public final PersonMapper persons;
    public final TenantWorkScope workScope;
    public final RecordVisibilityScopeControlAccess scopes;
    public final LegacyAiOutputCacheSql legacy;
    public final String defaultCatalog;
    public final String tenantCatalog;
    public final List<Integer> workspaceIds = new ArrayList<>();
    public final List<CacheCase> cacheCases = new ArrayList<>();
    public final int orgId;
    public final int foreignOrgId;
    public final int workspaceId;
    public final int userId;
    public final int personId;

    private final AnnotationConfigApplicationContext context;
    private final List<Integer> pipelineIds = new ArrayList<>();
    private final List<Integer> dealIds = new ArrayList<>();
    private boolean scratchCreated;

    /** Uses an already migrated default catalog; scratch catalogs receive no Flyway or control tables. */
    public AiOutputCachePlaneFixture(boolean split, int maximumPoolSize) throws Exception {
        String url = required("CONNEX_DB_URL");
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        if (defaultCatalog == null || !defaultCatalog.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("A migrated default catalog is required");
        }
        tenantCatalog = split ? "cnx_cache_plane_" + UUID.randomUUID().toString().replace("-", "") : defaultCatalog;
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(required("CONNEX_DB_USERNAME"));
        pool.setPassword(required("CONNEX_DB_PASSWORD"));
        pool.setMaximumPoolSize(maximumPoolSize);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("cache-plane");
        control = new JdbcTemplate(pool);
        if (control.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = 'flyway_schema_history'", Integer.class, defaultCatalog) != 1) {
            pool.close();
            throw new IllegalStateException("The default catalog must already be migrated");
        }
        var properties = new TenantRoutingProperties();
        properties.setMode(split ? TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT
            : TenantRoutingProperties.MODE_SINGLE_DATABASE);
        properties.setDefaultCatalog(defaultCatalog);
        routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        connections = new CountingDataSource(routing);
        transactions = new DataSourceTransactionManager(connections);
        tenant = new JdbcTemplate(connections);
        Configuration configuration = new Configuration(new Environment(
            "cache-plane", new SpringManagedTransactionFactory(), connections));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        configuration.addInterceptor(new ControlCatalogRoutingInterceptor(tenantContext, split));
        configuration.addInterceptor(new TenantScopeInterceptor(tenantContext, true, split));
        for (String resource : List.of("mappers/AiOutputCacheMapper.xml", "mappers/WorkspaceMapper.xml",
                "mappers/PersonMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        cache = sessions.getMapper(AiOutputCacheMapper.class);
        persons = sessions.getMapper(PersonMapper.class);
        WorkspaceMapper workspaces = sessions.getMapper(WorkspaceMapper.class);
        orgId = insert("INSERT INTO organization (name, slug) VALUES ('Cache Org', ?)", unique());
        foreignOrgId = insert("INSERT INTO organization (name, slug) VALUES ('Foreign Cache Org', ?)", unique());
        userId = insert("INSERT INTO app_user (username, display_name, email, password_hash, timezone) "
            + "VALUES (?, 'Cache Actor', ?, 'fixture-hash', 'UTC')", unique(), unique() + "@example.com");
        for (int index = 0; index < 6; index++) {
            addWorkspace(index == 3 || index == 5 ? foreignOrgId : orgId);
        }
        workspaceId = workspaceIds.getFirst();
        control.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", workspaceIds.get(4));
        int low = insert("INSERT INTO person (workspace_id, name) VALUES (?, 'Lower Peer')", workspaceId);
        personId = insert("INSERT INTO person (workspace_id, name) VALUES (?, 'Restricted Subject')", workspaceId);
        int high = insert("INSERT INTO person (workspace_id, name) VALUES (?, 'Upper Peer')", workspaceId);
        var catalogs = mock(TenantCatalogResolver.class);
        when(catalogs.isCatalogPerPlacement()).thenReturn(split);
        when(catalogs.resolveCatalog(orgId)).thenReturn(split ? tenantCatalog : null);
        context = new AnnotationConfigApplicationContext();
        context.register(ControlTransactions.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> transactions);
        context.registerBean(TenantWorkspaceScopeControlOperations.class, () ->
            new TenantWorkspaceScopeControlOperations(workspaces, mock(TenantLifecycleControlMapper.class), catalogs));
        context.refresh();
        workScope = new TenantWorkScope(tenantContext, catalogs, workspaces,
            mock(TenantLifecycleControlMapper.class), context.getBean(TenantWorkspaceScopeControlOperations.class));
        scopes = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(workspaces, workScope, tenantContext, transactions), tenantContext);
        legacy = new LegacyAiOutputCacheSql(connections, defaultCatalog);
        try {
            seedCache(low, high);
            if (split) {
                control.execute("CREATE DATABASE " + tenantCatalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                scratchCreated = true;
                for (String table : TENANT_TABLES) {
                    control.execute("CREATE TABLE " + tenantCatalog + "." + table + " LIKE " + defaultCatalog + "." + table);
                    control.update("INSERT INTO " + tenantCatalog + "." + table + " SELECT * FROM " + defaultCatalog
                        + "." + table + " WHERE " + fixtureRows(table));
                }
            }
        } catch (Exception | AssertionError failure) {
            try {
                close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Commits a new workspace using the fixture pool, independently of any caller transaction. */
    public int addWorkspace(int organizationId) throws SQLException {
        int id = insert("INSERT INTO workspace (org_id, name, slug) VALUES (?, 'Cache Workspace', ?)", organizationId, unique());
        workspaceIds.add(id);
        update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'owner')", id, userId);
        return id;
    }

    /** Commits a synthetic cache row independently and records its expected restriction outcome. */
    public int addCache(int workspace, String feature, int subjectA, int subjectB, boolean deleted) throws SQLException {
        int id = insert("INSERT INTO ai_output_cache (workspace_id, feature, subject_a_id, subject_b_id, "
            + "content_hash, payload, warnings, generated_at) VALUES (?, ?, ?, ?, ?, '{\"fixture\":true}', 0, ?)",
            workspace, feature, subjectA, subjectB, "a".repeat(64), "2026-10-01T00:00:00Z");
        cacheCases.add(new CacheCase(id, deleted));
        return id;
    }

    /** Returns every fixture cache column in stable order on the currently routed connection. */
    public List<Map<String, Object>> rows() {
        return tenant.queryForList("SELECT * FROM ai_output_cache WHERE " + fixtureRows("ai_output_cache") + " ORDER BY id");
    }

    /** Clears MyBatis local state after a JDBC savepoint rollback. */
    public void clearSessions() {
        sessions.clearCache();
        legacy.clearCache();
    }

    private void seedCache(int low, int high) throws SQLException {
        for (int workspace : List.of(workspaceIds.get(1), workspaceIds.get(3), workspaceIds.get(4))) {
            control.update("INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                personId, workspace, userId);
        }
        for (int index = 0; index < 5; index++) {
            int workspace = workspaceIds.get(index);
            boolean inOrg = index != 3;
            int pipeline = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, 'Cache Pipeline')", workspace);
            pipelineIds.add(pipeline);
            int stage = insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) VALUES (?, ?, 'Cache Stage', 0)",
                workspace, pipeline);
            List<Integer> deals = new ArrayList<>();
            for (int deal = 0; deal < 7; deal++) {
                int id = insert("INSERT INTO deal (workspace_id, pipeline_id, stage_id, name) VALUES (?, ?, ?, ?)",
                    workspace, pipeline, stage, "Cache Deal " + deal);
                deals.add(id);
                dealIds.add(id);
            }
            for (int peerIndex = 0; peerIndex < 3; peerIndex++) {
                int peer = peerIndex == 0 ? personId : peerIndex == 1 ? low : high;
                control.update("INSERT INTO deal_person (deal_id, person_id) VALUES (?, ?)", deals.get(peerIndex), peer);
            }
            for (int peer : List.of(low, high)) {
                control.update("INSERT INTO person_edge (workspace_id, source_person_id, target_person_id, type, strength) "
                    + "VALUES (?, ?, ?, 'knows', 2)", workspace, Math.min(peer, personId), Math.max(peer, personId));
            }
            for (int deal : List.of(deals.get(0), deals.get(3))) {
                control.update("INSERT INTO activity (workspace_id, person_id, deal_id, created_by_id, type, subject) "
                    + "VALUES (?, ?, ?, ?, 'call', 'Cache Link')", workspace, personId, deal, userId);
            }
            control.update("INSERT INTO note (workspace_id, person_id, deal_id, author_id, content) "
                + "VALUES (?, ?, ?, ?, 'Cache Link')", workspace, personId, deals.get(4), userId);
            control.update("INSERT INTO task (workspace_id, person_id, deal_id, assigned_to_id, description) "
                + "VALUES (?, ?, ?, ?, 'Cache Link')", workspace, personId, deals.get(5), userId);
            addCache(workspace, "report.narrative:v4:en", 4242, 0, inOrg);
            addCache(workspace, "intro.rationale:en", personId, low, inOrg);
            addCache(workspace, "intro.rationale:ja", high, personId, inOrg);
            addCache(workspace, "intro.rationale:en", low, high, false);
            addCache(workspace, "intro.rationale", personId, high, false);
            for (int deal = 0; deal < deals.size(); deal++) {
                addCache(workspace, "deal.brief:en", deals.get(deal), 0, inOrg && deal < 6);
                addCache(workspace, "deal.risk_rationale:ja", deals.get(deal), 0, inOrg && deal < 3);
            }
            addCache(workspace, "deal.riskXrationale:en", deals.getFirst(), 0, inOrg);
        }
        int foreign = workspaceIds.get(3);
        for (String feature : List.of("deal.brief:en", "deal.risk_rationale:ja")) {
            addCache(foreign, feature, dealIds.getFirst(), 0, false);
        }
        int deletedWorkspace = workspaceIds.get(5);
        control.update("DELETE FROM workspace_member WHERE workspace_id = ?", deletedWorkspace);
        control.update("DELETE FROM workspace WHERE id = ?", deletedWorkspace);
        for (String feature : List.of("report.narrative:v4:en", "intro.rationale:en",
                "deal.brief:en", "deal.risk_rationale:ja")) {
            addCache(deletedWorkspace, feature, feature.startsWith("intro") ? personId : dealIds.getFirst(), 0, false);
        }
    }

    private int insert(String sql, Object... parameters) throws SQLException {
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Fixture insert returned no key");
                }
                return keys.getInt(1);
            }
        }
    }

    private void update(String sql, Object... parameters) throws SQLException {
        try (Connection connection = pool.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private String fixtureRows(String table) {
        String workspaces = workspaceIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return table.equals("deal_person") ? "deal_id IN (SELECT id FROM deal WHERE workspace_id IN (" + workspaces + "))"
            : "workspace_id IN (" + workspaces + ")";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException(name + " is required for database-backed cache tests");
        }
        return value;
    }

    private static String unique() {
        return "cache-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void close() {
        tenantContext.clear();
        try {
            if (scratchCreated) {
                control.execute("DROP DATABASE " + tenantCatalog);
            }
            for (String table : List.of("ai_output_cache", "person_edge", "activity", "note", "task", "deal_person")) {
                control.update("DELETE FROM " + table + " WHERE " + fixtureRows(table));
            }
            control.update("DELETE FROM person_share WHERE person_id = ?", personId);
            control.update("DELETE FROM deal WHERE " + fixtureRows("deal"));
            for (int pipeline : pipelineIds) {
                control.update("DELETE FROM stage WHERE pipeline_id = ?", pipeline);
                control.update("DELETE FROM pipeline WHERE id = ?", pipeline);
            }
            control.update("DELETE FROM person WHERE " + fixtureRows("person"));
            for (int workspace : workspaceIds) {
                TestEntityFixtures.deleteChatPrivacy(control, workspace);
                control.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace);
                control.update("DELETE FROM workspace WHERE id = ?", workspace);
            }
            for (int organization : List.of(orgId, foreignOrgId)) {
                control.update("DELETE FROM organization_duplicate_decision_lock WHERE organization_id = ?", organization);
                control.update("DELETE FROM organization WHERE id = ?", organization);
            }
            control.update("DELETE FROM app_user WHERE id = ?", userId);
        } finally {
            context.close();
            pool.close();
        }
    }

    /** A cache row's independently declared expected deletion outcome. */
    public record CacheCase(int id, boolean deleted) { }

    /** Counts tenant/control preparation checkouts without exposing connection credentials. */
    public static final class CountingDataSource extends DelegatingDataSource {
        public final AtomicInteger checkouts = new AtomicInteger();

        private CountingDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            checkouts.incrementAndGet();
            return super.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            checkouts.incrementAndGet();
            return super.getConnection(username, password);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class ControlTransactions { }
}
