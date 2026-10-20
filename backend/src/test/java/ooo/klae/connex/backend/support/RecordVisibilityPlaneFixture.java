package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.config.AuditLogV126MigrationCallback;
import ooo.klae.connex.backend.config.TenantRoutingConfig;
import ooo.klae.connex.backend.mappers.DuplicateReviewMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Minimal real-MySQL fixtures for the pre-#485 employer-visibility matrix on either catalog layout. */
public final class RecordVisibilityPlaneFixture implements AutoCloseable {
    public final TenantContext tenantContext = new TenantContext();
    public final String defaultCatalog;
    public final String tenantCatalog;
    public final HikariDataSource pool;
    public final TenantRoutingDataSource routing;
    public final DataSourceTransactionManager transactionManager;
    public final JdbcTemplate controlJdbc;
    public final IntroductionMapper introductionMapper;
    public final DuplicateReviewMapper duplicateReviewMapper;
    public final RecordVisibilityScopeControlAccess scopeAccess;
    public final LegacyRecordVisibilitySql legacy;
    public final List<Integer> workspaceIds = new ArrayList<>();
    public final List<Integer> companyIds = new ArrayList<>();
    public final List<Integer> personIds = new ArrayList<>();
    public final List<Long> pairIds = new ArrayList<>();
    public int orgId;
    public int foreignOrgId;
    public int workspaceId;
    public int userId;

    private final List<String> tables;
    private final boolean split;

    /** Clones only the tables referenced by the selected mapper's visibility statements. */
    public RecordVisibilityPlaneFixture(boolean introductions, boolean split) throws Exception {
        this(introductions, split, 2);
    }

    /** Creates the same minimal fixture with an explicit connection budget. */
    public RecordVisibilityPlaneFixture(boolean introductions, boolean split, int maximumPoolSize) throws Exception {
        this.split = split;
        String url = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Database credentials are required for record-visibility tests");
        }
        defaultCatalog = TenantRoutingConfig.databaseFromJdbcUrl(url);
        if (defaultCatalog == null || !defaultCatalog.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("A migrated default catalog is required");
        }
        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .callbacks(new AuditLogV126MigrationCallback())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
        tenantCatalog = split ? "cnx_record_plane_" + UUID.randomUUID().toString().replace("-", "")
            : defaultCatalog;
        tables = introductions
            ? List.of("company", "person", "company_share", "activity", "note", "task", "person_edge")
            : List.of("company", "person", "company_share", "person_identity", "company_identity",
                "duplicate_review_decision");
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(maximumPoolSize);
        pool.setConnectionTimeout(5_000);
        pool.setPoolName("record-visibility-plane");
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(split ? TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT
            : TenantRoutingProperties.MODE_SINGLE_DATABASE);
        properties.setDefaultCatalog(defaultCatalog);
        routing = TenantRoutingConfig.decorate(pool, properties, tenantContext);
        transactionManager = new DataSourceTransactionManager(routing);
        controlJdbc = new JdbcTemplate(pool);
        Configuration configuration = new Configuration(new Environment(
            "record-visibility-plane", new SpringManagedTransactionFactory(), routing));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/IntroductionMapper.xml", "mappers/DuplicateReviewMapper.xml",
                "mappers/WorkspaceMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        introductionMapper = sessions.getMapper(IntroductionMapper.class);
        duplicateReviewMapper = sessions.getMapper(DuplicateReviewMapper.class);
        TenantWorkScope workScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        scopeAccess = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(
            sessions.getMapper(WorkspaceMapper.class), workScope, tenantContext, transactionManager));
        legacy = new LegacyRecordVisibilitySql(pool, defaultCatalog);
        try {
            seedControl();
            seedRecords(introductions);
            if (split) {
                controlJdbc.execute("CREATE DATABASE " + tenantCatalog
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                for (String table : tables) {
                    controlJdbc.execute("CREATE TABLE " + tenantCatalog + "." + table
                        + " LIKE " + defaultCatalog + "." + table);
                    controlJdbc.update("INSERT INTO " + tenantCatalog + "." + table + " SELECT * FROM "
                        + defaultCatalog + "." + table + " WHERE workspace_id IN (" + workspaceIdsSql() + ")");
                }
                controlJdbc.update("INSERT INTO org_placement (org_id, placement_mode, database_handle, "
                    + "storage_encryption_mode, key_controller, revocation_supported) "
                    + "VALUES (?, 'dedicated_database', ?, 'provider_managed', 'connex_cloud_provider', FALSE)",
                    orgId, tenantCatalog);
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

    /** Installs the fixture's validated tenant identity without changing its control-plane data. */
    public void installTenant() {
        tenantContext.set(workspaceId, orgId, userId, "member", split ? tenantCatalog : null);
    }

    /** Parameters for the frozen pre-#485 statements, which always use the default catalog. */
    public Map<String, Object> parameters() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", workspaceId);
        parameters.put("maximumPairGroupSize", 20);
        parameters.put("limit", 100);
        parameters.put("offset", 0L);
        parameters.put("recordType", null);
        parameters.put("kind", null);
        parameters.put("state", null);
        return parameters;
    }

    /** Inspects catalog contents without routing a query through a different transaction. */
    public boolean tenantTableExists(String table) throws SQLException {
        try (Connection connection = pool.getConnection();
                ResultSet rows = connection.getMetaData().getTables(tenantCatalog, null, table, null)) {
            return rows.next();
        }
    }

    private void seedControl() throws SQLException {
        orgId = insert("INSERT INTO organization (name, slug) VALUES (?, ?)", "Visibility Org", unique());
        foreignOrgId = insert("INSERT INTO organization (name, slug) VALUES (?, ?)", "Foreign Org", unique());
        for (int i = 0; i < 5; i++) {
            workspaceIds.add(insert("INSERT INTO workspace (org_id, name, slug) VALUES (?, ?, ?)",
                i == 3 ? foreignOrgId : orgId, "Visibility Workspace " + i, unique()));
        }
        workspaceId = workspaceIds.getFirst();
        controlJdbc.update("UPDATE workspace SET lifecycle_state = 'tearing_down' WHERE id = ?", workspaceIds.get(4));
        userId = insert("INSERT INTO app_user (username, display_name, email, password_hash, timezone) "
            + "VALUES (?, ?, ?, ?, 'UTC')", unique(), "Visibility Member", unique() + "@example.com", "test-hash");
        controlJdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'member')",
            workspaceId, userId);
    }

    private void seedRecords(boolean introductions) throws SQLException {
        int[] owners = {workspaceId, workspaceIds.get(1), workspaceIds.get(2), workspaceIds.get(3),
            workspaceIds.get(4), workspaceId};
        for (int i = 0; i < owners.length; i++) {
            companyIds.add(insert("INSERT INTO company (workspace_id, name) VALUES (?, ?)",
                owners[i], "Employer " + i));
        }
        controlJdbc.update("UPDATE company SET archived_at = '2026-01-01' WHERE id = ?", companyIds.get(5));
        for (int i : List.of(1, 3, 4)) {
            controlJdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                companyIds.get(i), workspaceId, userId);
        }
        for (int i = 0; i < 7; i++) {
            int first = person(i);
            if (introductions) {
                controlJdbc.update("INSERT INTO note (workspace_id, author_id, person_id, content, visibility) "
                    + "VALUES (?, ?, ?, 'engagement', 'workspace')", workspaceId, userId, first);
            } else {
                int second = person(i);
                long identity = personIdentity(first, "pair-" + i + "@example.com");
                personIdentity(second, "pair-" + i + "@example.com");
                pairIds.add(insertLong("INSERT INTO duplicate_review_decision (workspace_id, record_type, kind, "
                    + "evidence_fingerprint, low_person_id, high_person_id, evidence_person_identity_id, collision_size, "
                    + "state, detected_at) VALUES (?, 'person', 'email', ?, ?, ?, ?, 2, ?, '2026-09-02 12:00:00')",
                    workspaceId, fingerprint(i + 1), first, second, identity, i == 1 ? "dismissed" : "open"));
            }
        }
        if (!introductions) {
            int secondCompany = insert("INSERT INTO company (workspace_id, name) VALUES (?, 'Company Review Peer')",
                workspaceId);
            long identity = insertLong("INSERT INTO company_identity (workspace_id, company_id, kind, `value`, "
                + "normalized_value, source_system, source_channel, acquired_at) "
                + "VALUES (?, ?, 'domain', 'review.example', 'review.example', 'csv_import', 'company.website', NOW())",
                workspaceId, companyIds.getFirst());
            insertLong("INSERT INTO duplicate_review_decision (workspace_id, record_type, kind, "
                + "evidence_fingerprint, low_company_id, high_company_id, evidence_company_identity_id, collision_size, "
                + "detected_at) VALUES (?, 'company', 'domain', ?, ?, ?, ?, 2, '2026-09-02 12:00:00')",
                workspaceId, fingerprint(100), companyIds.getFirst(), secondCompany, identity);
            long personIdentity = controlJdbc.queryForObject("SELECT MIN(id) FROM person_identity WHERE workspace_id = ?",
                Long.class, workspaceId);
            insertLong("INSERT INTO duplicate_review_decision (workspace_id, record_type, kind, "
                + "evidence_fingerprint, evidence_person_identity_id, oversized_marker, collision_size, detected_at) "
                + "VALUES (?, 'person', 'email', ?, ?, 1, 21, '2026-09-02 12:00:00')",
                workspaceId, fingerprint(101), personIdentity);
        }
    }

    private int person(int employerCase) throws SQLException {
        int person = insert("INSERT INTO person (workspace_id, name, title, company_id) VALUES (?, ?, ?, ?)",
            workspaceId, "Candidate " + personIds.size(), "Director", employerCase == 6 ? null : companyIds.get(employerCase));
        personIds.add(person);
        return person;
    }

    private long personIdentity(int personId, String value) throws SQLException {
        return insertLong("INSERT INTO person_identity (workspace_id, person_id, kind, `value`, normalized_value, "
            + "source_system, source_channel, acquired_at) VALUES (?, ?, 'email', ?, ?, 'csv_import', 'person.email', NOW())",
            workspaceId, personId, value, value);
    }

    private int insert(String sql, Object... parameters) throws SQLException {
        return Math.toIntExact(insertLong(sql, parameters));
    }

    private long insertLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = pool.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Fixture insert returned no key");
                }
                return keys.getLong(1);
            }
        }
    }

    /** Stable evidence used to address an exact seeded decision. */
    public static String fingerprint(int value) {
        return "%064x".formatted(value);
    }

    private String workspaceIdsSql() {
        return workspaceIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static String unique() {
        return "visibility-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public void close() {
        tenantContext.clear();
        try {
            for (int organization : List.of(orgId, foreignOrgId)) {
                if (organization != 0) {
                    controlJdbc.update("DELETE FROM org_placement WHERE org_id = ?", organization);
                }
            }
            if (split) {
                controlJdbc.execute("DROP DATABASE IF EXISTS " + tenantCatalog);
            }
            if (!workspaceIds.isEmpty()) {
                for (String table : tables.reversed()) {
                    controlJdbc.update("DELETE FROM " + table + " WHERE workspace_id IN (" + workspaceIdsSql() + ")");
                }
                controlJdbc.update("DELETE FROM workspace_member WHERE workspace_id IN (" + workspaceIdsSql() + ")");
                controlJdbc.update("DELETE FROM workspace WHERE id IN (" + workspaceIdsSql() + ")");
            }
            for (int organization : List.of(orgId, foreignOrgId)) {
                if (organization != 0) {
                    controlJdbc.update("DELETE FROM organization_duplicate_decision_lock WHERE organization_id = ?", organization);
                    controlJdbc.update("DELETE FROM organization WHERE id = ?", organization);
                }
            }
            if (userId != 0) {
                controlJdbc.update("DELETE FROM notification_recipient_state WHERE recipient_id = ?", userId);
                controlJdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        } finally {
            pool.close();
        }
    }
}
