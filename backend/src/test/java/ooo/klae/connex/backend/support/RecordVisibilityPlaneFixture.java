package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
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
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Minimal real-MySQL fixtures for pre-#485 record-visibility matrices on either catalog layout. */
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
    public final NoteMapper noteMapper;
    public final SegmentMapper segmentMapper;
    public final RecordVisibilityScopeControlAccess scopeAccess;
    public final LegacyRecordVisibilitySql legacy;
    public final List<Integer> workspaceIds = new ArrayList<>();
    public final List<Integer> companyIds = new ArrayList<>();
    public final List<Integer> personIds = new ArrayList<>();
    public final List<Long> pairIds = new ArrayList<>();
    public final List<Integer> noteIds = new ArrayList<>();
    public final List<Integer> privateNoteIds = new ArrayList<>();
    public final List<Integer> dealIds = new ArrayList<>();
    public final List<Integer> candidateCompanyIds = new ArrayList<>();
    public final List<Integer> candidatePersonIds = new ArrayList<>();
    public final List<Integer> candidateDealIds = new ArrayList<>();
    public final List<Integer> companyFieldPersonIds = new ArrayList<>();
    public int orgId;
    public int foreignOrgId;
    public int workspaceId;
    public int userId;
    public int otherUserId;

    private final List<String> tables;
    private final boolean split;
    private int pipelineId;

    /** Selects one mapper's minimal tenant-table fixture without changing the pilot fixtures. */
    public enum Records {
        INTRODUCTIONS, DUPLICATE_REVIEW, NOTES, SEGMENTS
    }

    /** Clones only the tables referenced by the selected mapper's visibility statements. */
    public RecordVisibilityPlaneFixture(boolean introductions, boolean split) throws Exception {
        this(introductions ? Records.INTRODUCTIONS : Records.DUPLICATE_REVIEW, split);
    }

    /** Migrates the default schema and clones only the selected statements' tenant tables. */
    public RecordVisibilityPlaneFixture(Records records, boolean split) throws Exception {
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
        tables = switch (records) {
            case INTRODUCTIONS -> List.of("company", "person", "company_share", "activity", "note", "task", "person_edge");
            case DUPLICATE_REVIEW -> List.of("company", "person", "company_share", "person_identity", "company_identity",
                "duplicate_review_decision");
            case NOTES -> List.of("company", "company_share", "person", "person_share", "deal", "note");
            case SEGMENTS -> List.of("company", "company_share", "person", "person_share", "deal", "note",
                "activity", "person_edge", "task");
        };
        pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(2);
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
                "mappers/WorkspaceMapper.xml", "mappers/NoteMapper.xml", "mappers/SegmentMapper.xml")) {
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
        noteMapper = sessions.getMapper(NoteMapper.class);
        segmentMapper = sessions.getMapper(SegmentMapper.class);
        TenantWorkScope workScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        scopeAccess = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(
            sessions.getMapper(WorkspaceMapper.class), workScope, tenantContext, transactionManager), tenantContext);
        legacy = new LegacyRecordVisibilitySql(pool, defaultCatalog);
        try {
            seedControl();
            if (records == Records.NOTES || records == Records.SEGMENTS) {
                seedNotes();
                if (records == Records.SEGMENTS) {
                    seedSegments();
                }
            } else {
                seedRecords(records == Records.INTRODUCTIONS);
            }
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

    /** Parameters shared by the seven rewritten Note statements and their frozen SQL oracle. */
    public Map<String, Object> noteParameters() {
        Map<String, Object> parameters = parameters();
        parameters.put("currentUserId", userId);
        parameters.put("query", null);
        parameters.put("authorIds", List.of());
        parameters.put("sort", "updated");
        parameters.put("dir", "desc");
        parameters.put("personId", personIds.getFirst());
        parameters.put("companyId", companyIds.getFirst());
        parameters.put("reference", LocalDateTime.of(2026, 10, 1, 0, 0));
        parameters.put("sourceLimit", 100);
        return parameters;
    }

    /** Parameters for company predicates and their frozen Segment SQL oracle. */
    public Map<String, Object> segmentParameters() {
        Map<String, Object> parameters = parameters();
        parameters.put("userId", userId);
        parameters.put("days", 30);
        parameters.put("personIds", candidatePersonIds);
        parameters.put("candidateIds", candidateCompanyIds);
        parameters.put("strongEdge", 2);
        parameters.put("field", "company");
        parameters.put("op", "in");
        parameters.put("ids", companyIds);
        parameters.put("id", companyIds.getFirst());
        return parameters;
    }

    /** Adds attributed Segment evidence to the default catalog before any optional scratch clone. */
    public int addActivity(int ownerWorkspaceId, int actorId, Integer personId, Integer dealId, String timestamp)
            throws SQLException {
        return insert("INSERT INTO activity (workspace_id, created_by_id, person_id, deal_id, type, subject, timestamp) "
            + "VALUES (?, ?, ?, ?, 'call', 'Segment evidence', ?)", ownerWorkspaceId, actorId, personId, dealId, timestamp);
    }

    /** Inserts deterministic note evidence in the default fixture before a scratch catalog is cloned. */
    public int addNote(int ownerWorkspaceId, int authorId, Integer personId, Integer dealId,
            String visibility, String title, String content, String timestamp) throws SQLException {
        return insert("INSERT INTO note (workspace_id, author_id, person_id, deal_id, visibility, title, content, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", ownerWorkspaceId, authorId,
            personId, dealId, visibility, title, content, timestamp, timestamp);
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

    private void seedNotes() throws SQLException {
        otherUserId = insert("INSERT INTO app_user (username, display_name, email, password_hash, timezone) "
            + "VALUES (?, ?, ?, ?, 'UTC')", unique(), "Other Visibility Member", unique() + "@example.com", "test-hash");
        controlJdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'member')",
            workspaceId, otherUserId);
        int[] owners = {workspaceId, workspaceIds.get(1), workspaceIds.get(2), workspaceIds.get(3),
            workspaceIds.get(4), workspaceId};
        for (int i = 0; i < owners.length; i++) {
            companyIds.add(insert("INSERT INTO company (workspace_id, name) VALUES (?, ?)",
                owners[i], "Note Employer " + i));
        }
        controlJdbc.update("UPDATE company SET archived_at = '2026-01-01' WHERE id = ?", companyIds.get(5));
        for (int i : List.of(1, 3, 4)) {
            controlJdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                companyIds.get(i), workspaceId, userId);
        }
        pipelineId = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, 'Note Evidence Pipeline')", workspaceId);
        int stageId = insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) VALUES (?, ?, 'Evidence', 0)",
            workspaceId, pipelineId);
        for (int company : companyIds) {
            dealIds.add(insert("INSERT INTO deal (workspace_id, pipeline_id, stage_id, company_id, name) "
                + "VALUES (?, ?, ?, ?, 'Note Evidence Deal')", workspaceId, pipelineId, stageId, company));
        }
        for (int i = 0; i < 8; i++) {
            int owner = i < owners.length ? owners[i] : workspaceId;
            int company = companyIds.get(i < owners.length ? i : 0);
            int person = insert("INSERT INTO person (workspace_id, name, company_id) VALUES (?, ?, ?)",
                owner, "LinkedNeedle " + i, company);
            personIds.add(person);
            if (List.of(1, 3, 4).contains(i)) {
                controlJdbc.update("INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    person, workspaceId, userId);
            }
            String timestamp = "2026-09-%02d 12:00:00".formatted(i + 1);
            noteIds.add(addNote(workspaceId, userId, person, null, "workspace", "Public " + i, "body " + i, timestamp));
            privateNoteIds.add(addNote(workspaceId, userId, person, null, "private", "Private " + i, "private body " + i, timestamp));
            addNote(workspaceId, otherUserId, person, null, "private", "Colleague " + i, "colleague body " + i, timestamp);
            if (owner != workspaceId) {
                addNote(owner, userId, person, null, "workspace", "Other workspace " + i, "other body", timestamp);
            }
        }
        controlJdbc.update("UPDATE person SET archived_at = '2026-01-01' WHERE id = ?", personIds.get(5));
        controlJdbc.update("UPDATE person SET provision_ceased_at = '2026-01-01' WHERE id = ?", personIds.get(6));
        controlJdbc.update("UPDATE person SET suspended_at = '2026-01-01' WHERE id = ?", personIds.get(7));
    }

    private void seedSegments() throws SQLException {
        int stageId = controlJdbc.queryForObject("SELECT id FROM stage WHERE pipeline_id = ?", Integer.class, pipelineId);
        for (int i = 0; i < personIds.size(); i++) {
            int company = insert("INSERT INTO company (workspace_id, name) VALUES (?, ?)",
                workspaceId, "Segment Candidate Company " + i);
            candidateCompanyIds.add(company);
            int person = insert("INSERT INTO person (workspace_id, name, company_id) VALUES (?, ?, ?)",
                workspaceId, "Segment Candidate " + i, company);
            candidatePersonIds.add(person);
            int deal = insert("INSERT INTO deal (workspace_id, pipeline_id, stage_id, company_id, name) "
                + "VALUES (?, ?, ?, ?, 'Segment Evidence Deal')", workspaceId, pipelineId, stageId, company);
            candidateDealIds.add(deal);
            controlJdbc.update("INSERT INTO person_edge (workspace_id, source_person_id, target_person_id, type, strength) "
                + "VALUES (?, ?, ?, 'knows', 2)", workspaceId, person, personIds.get(i));
            addActivity(workspaceId, otherUserId, personIds.get(i), deal, "2999-01-01 00:00:00");
        }
        for (int company : companyIds) {
            companyFieldPersonIds.add(insert("INSERT INTO person (workspace_id, name, company_id) VALUES (?, ?, ?)",
                workspaceId, "Segment Company Field " + company, company));
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
                if (pipelineId != 0) {
                    controlJdbc.update("DELETE FROM stage WHERE pipeline_id = ?", pipelineId);
                    controlJdbc.update("DELETE FROM pipeline WHERE id = ?", pipelineId);
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
            for (int user : List.of(userId, otherUserId)) {
                if (user != 0) {
                    controlJdbc.update("DELETE FROM notification_recipient_state WHERE recipient_id = ?", user);
                    controlJdbc.update("DELETE FROM app_user WHERE id = ?", user);
                }
            }
        } finally {
            pool.close();
        }
    }
}
