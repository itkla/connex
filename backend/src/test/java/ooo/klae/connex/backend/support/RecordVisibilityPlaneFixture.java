package ooo.klae.connex.backend.support;

import ooo.klae.connex.backend.mappers.CompanyMapper;

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
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.ReportMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Minimal real-MySQL fixtures for pre-#485 record-visibility matrices on either catalog layout. */
public final class RecordVisibilityPlaneFixture implements AutoCloseable {
    public final TenantContext tenantContext = new TenantContext();
    public final String defaultCatalog;
    public final String tenantCatalog;
    public final HikariDataSource pool;
    public final TenantRoutingDataSource routing;
    public final DataSourceTransactionManager transactionManager;
    public final JdbcTemplate controlJdbc;
    public final WorkspaceMapper workspaceMapper;
    public final IntroductionMapper introductionMapper;
    public final DuplicateReviewMapper duplicateReviewMapper;
    public final NoteMapper noteMapper;
    public final ReportMapper reportMapper;
    public final SegmentMapper segmentMapper;
    public final PipelineMapper pipelineMapper;
    public final DealMapper dealMapper;
    public final SqlSessionTemplate dealStatements;
    public final CompanyMapper companyMapper;
    public final SqlSessionTemplate companyStatements;
    public final RecordVisibilityScopeControlAccess scopeAccess;
    public final LegacyRecordVisibilitySql legacy;
    public final List<Integer> pipelineIds = new ArrayList<>();
    public final List<Integer> stageIds = new ArrayList<>();
    public final List<Integer> workspaceIds = new ArrayList<>();
    public final List<Integer> companyIds = new ArrayList<>();
    public final List<Integer> personIds = new ArrayList<>();
    public final List<Long> pairIds = new ArrayList<>();
    public final List<Integer> noteIds = new ArrayList<>();
    public final List<Integer> privateNoteIds = new ArrayList<>();
    public final List<Integer> dealIds = new ArrayList<>();
    public final List<Integer> reportEvidencePersonIds = new ArrayList<>();
    public int reportTagId;
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
    private int reportStageId;

    /** Selects one mapper's minimal tenant-table fixture without changing the pilot fixtures. */
    public enum Records {
        INTRODUCTIONS, DUPLICATE_REVIEW, NOTES, REPORTS, SEGMENTS, PIPELINES, COMPANIES, DEALS
    }

    /** Migrates the default schema and clones only the selected statements' tenant tables. */
    public RecordVisibilityPlaneFixture(Records records, boolean split) throws Exception {
        this(records, split, 2);
    }

    /** Creates the same minimal fixture with an explicit connection budget. */
    public RecordVisibilityPlaneFixture(Records records, boolean split, int maximumPoolSize) throws Exception {
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
            case DEALS -> List.of("company", "person", "company_share", "person_share",
                "pipeline", "pipeline_share", "stage", "deal", "deal_person");
            case COMPANIES -> List.of("company", "person", "company_share", "person_share", "deal", "tag",
                "activity", "note", "task", "company_tag");
            case PIPELINES -> List.of("pipeline", "pipeline_share", "stage");
            case INTRODUCTIONS -> List.of("company", "person", "company_share", "activity", "note", "task", "person_edge");
            case DUPLICATE_REVIEW -> List.of("company", "person", "company_share", "person_identity", "company_identity",
                "duplicate_review_decision");
            case NOTES -> List.of("company", "company_share", "person", "person_share", "deal", "note");
            case REPORTS -> List.of("company", "person", "company_share", "person_share", "deal", "tag",
                "activity", "note", "task", "company_tag", "deal_person", "deal_tag");
            case SEGMENTS -> List.of("company", "company_share", "person", "person_share", "deal", "note",
                "activity", "person_edge", "task");
        };
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
                "mappers/WorkspaceMapper.xml", "mappers/NoteMapper.xml", "mappers/ReportMapper.xml",
                "mappers/SegmentMapper.xml", "mappers/PipelineMapper.xml", "mappers/CompanyMapper.xml", "mappers/PersonMapper.xml", "mappers/DealMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing mapper resource " + resource);
                }
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        workspaceMapper = sessions.getMapper(WorkspaceMapper.class);
        introductionMapper = sessions.getMapper(IntroductionMapper.class);
        duplicateReviewMapper = sessions.getMapper(DuplicateReviewMapper.class);
        noteMapper = sessions.getMapper(NoteMapper.class);
        reportMapper = sessions.getMapper(ReportMapper.class);
        segmentMapper = sessions.getMapper(SegmentMapper.class);
        pipelineMapper = sessions.getMapper(PipelineMapper.class);
        companyMapper = sessions.getMapper(CompanyMapper.class);
        companyStatements = sessions;
        dealStatements = sessions;
        dealMapper = sessions.getMapper(DealMapper.class);
        TenantWorkScope workScope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        scopeAccess = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(
            workspaceMapper, workScope, tenantContext, transactionManager), tenantContext);
        legacy = new LegacyRecordVisibilitySql(pool, defaultCatalog);
        try {
            seedControl();
            if (records == Records.DEALS) {
                seedDeals();
            } else if (records == Records.COMPANIES) {
                seedCompanies();
            } else if (records == Records.PIPELINES) {
                seedPipelines();
            } else if (records == Records.NOTES || records == Records.SEGMENTS) {
                seedNotes();
                if (records == Records.SEGMENTS) {
                    seedSegments();
                }
            } else if (records == Records.REPORTS) {
                seedReports();
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
                        + defaultCatalog + "." + table + " WHERE " + fixtureRows(table));
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

    /** Builds deterministic report parameters also accepted by the frozen legacy statements. */
    public Map<String, Object> reportParameters(String measure, String group, LocalDateTime end, int limit) {
        return reportParameters(measure, group, end, limit, false);
    }

    /** Also exercises every optional tenant-side filter using the fixture's matching roots. */
    public Map<String, Object> reportParameters(
            String measure, String group, LocalDateTime end, int limit, boolean filtered) {
        LocalDateTime start = end.minusMonths(1);
        ReportAggregateQuery query = new ReportAggregateQuery(workspaceId, measure, group, "month", start, end,
            start.toLocalDate(), end.toLocalDate(), filtered ? List.of(pipelineId) : null,
            filtered ? List.of(userId) : null, filtered ? List.of("open") : null,
            filtered ? List.of(reportTagId) : null, filtered ? List.copyOf(dealIds) : null, null, 10,
            List.of(new ReportOffsetSegment(start, end, 0)));
        Map<String, Object> parameters = parameters();
        parameters.put("query", query);
        parameters.put("warmthReference", end.minusNanos(1_000_000));
        parameters.put("model", RelationshipWarmthModel.current().sqlParameters());
        parameters.put("asOf", end);
        parameters.put("limit", limit);
        return parameters;
    }

    /** Adds a report stakeholder without changing the fixed owner/share matrix lists. */
    public int addReportPerson(int ownerWorkspaceId, int companyId, String name, LocalDateTime createdAt)
            throws SQLException {
        return insert("INSERT INTO person (workspace_id, company_id, name, created_at) VALUES (?, ?, ?, ?)",
            ownerWorkspaceId, companyId, name, createdAt);
    }

    /** Adds one viewer-owned meeting used to test eligible warmth before aggregation. */
    public int addReportTouch(int personId, LocalDateTime timestamp) throws SQLException {
        return insert("INSERT INTO activity (workspace_id, person_id, created_by_id, type, subject, timestamp) "
            + "VALUES (?, ?, ?, 'meeting', 'Report warmth', ?)", workspaceId, personId, userId, timestamp);
    }

    /** Adds a workspace-owned open deal without a stakeholder link. */
    public int addReportDeal(int companyId, String name, String value, String currency) throws SQLException {
        return insert("INSERT INTO deal (workspace_id, pipeline_id, stage_id, company_id, name, value, currency, "
            + "owner_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2026-08-01 00:00:00')", workspaceId, pipelineId,
            reportStageId, companyId, name, value, currency, userId);
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

    private void seedDeals() throws SQLException {
        seedPipelines();
        for (int index = 0; index < 8; index++) {
            int owner = index < 5 ? workspaceIds.get(index) : workspaceId;
            int company = insert("INSERT INTO company (workspace_id, name) VALUES (?, ?)",
                owner, "Employer Needle " + index);
            companyIds.add(company);
            int person = insert("INSERT INTO person (workspace_id, company_id, name, email) VALUES (?, ?, ?, ?)",
                owner, company, "Contact " + index, "deal-" + index + "@example.com");
            personIds.add(person);
            if (List.of(1, 3, 4).contains(index)) {
                controlJdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    company, workspaceId, userId);
                controlJdbc.update("INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    person, workspaceId, userId);
            }
            int relation = Math.min(index, 4);
            int deal = insert("INSERT INTO deal (workspace_id, name, value, actual_value, currency, company_id, "
                + "pipeline_id, stage_id, position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-01-01')",
                workspaceId, "Deal " + index, 100 + index, index, index % 2 == 0 ? "USD" : "JPY",
                company, pipelineIds.get(relation), stageIds.get(relation * 2), index);
            dealIds.add(deal);
            controlJdbc.update("INSERT INTO deal_person (deal_id, person_id, role) VALUES (?, ?, ?)",
                deal, person, "Role " + index);
        }
        controlJdbc.update("UPDATE company SET archived_at = '2026-01-02' WHERE id = ?", companyIds.get(5));
        controlJdbc.update("UPDATE person SET archived_at = '2026-01-02' WHERE id = ?", personIds.get(5));
        controlJdbc.update("UPDATE person SET suspended_at = '2026-01-02' WHERE id = ?", personIds.get(6));
        controlJdbc.update("UPDATE person SET provision_ceased_at = '2026-01-02' WHERE id = ?", personIds.get(7));
        for (int index = 1; index < personIds.size(); index++) {
            controlJdbc.update("INSERT INTO deal_person (deal_id, person_id, role) VALUES (?, ?, ?)",
                dealIds.getFirst(), personIds.get(index), "Other " + index);
        }
        for (int index = 0; index < 5; index++) {
            insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) VALUES (?, ?, ?, 1)",
                workspaceIds.get(index), pipelineIds.get(index), "Last Normal " + index);
        }
        int foreignDeal = insert("INSERT INTO deal (workspace_id, name, value, currency, pipeline_id, stage_id) "
            + "VALUES (?, 'Foreign private deal', 1, 'USD', ?, ?)",
            workspaceIds.get(2), pipelineIds.get(2), stageIds.get(4));
        dealIds.add(foreignDeal);
        controlJdbc.update("INSERT INTO deal_person (deal_id, person_id) VALUES (?, ?)", foreignDeal, personIds.getFirst());
    }

    private void seedPipelines() throws SQLException {
        for (int index = 0; index < workspaceIds.size(); index++) {
            int owner = workspaceIds.get(index);
            int pipeline = addPipeline(owner, "Pipeline Needle " + index);
            stageIds.add(insert("INSERT INTO stage (workspace_id, pipeline_id, name, position, is_success) "
                + "VALUES (?, ?, ?, 2, TRUE)", owner, pipeline, "Later Stage " + index));
            stageIds.add(insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) "
                + "VALUES (?, ?, ?, 0)", owner, pipeline, "First Stage " + index));
            if (List.of(1, 3, 4).contains(index)) {
                controlJdbc.update("INSERT INTO pipeline_share (pipeline_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    pipeline, workspaceId, userId);
            }
        }
        addPipeline(workspaceId, "Empty Pipeline");
    }

    /** Adds a pipeline to the frozen default-catalog fixture before any optional scratch clone. */
    public int addPipeline(int ownerWorkspaceId, String name) throws SQLException {
        int pipeline = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, ?)", ownerWorkspaceId, name);
        pipelineIds.add(pipeline);
        return pipeline;
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

    private void seedCompanies() throws SQLException {
        int[] owners = {workspaceId, workspaceIds.get(1), workspaceIds.get(2), workspaceIds.get(3),
            workspaceIds.get(4), workspaceId};
        String[] names = {"Company A Owned", "Company B Shared", "0 Company Hidden Sibling",
            "0 Company Hidden Foreign", "Company C Winding", "Company D Archived"};
        pipelineId = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, 'Company Evidence Pipeline')", workspaceId);
        reportStageId = insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) "
            + "VALUES (?, ?, 'Company Evidence Open', 0)", workspaceId, pipelineId);
        reportTagId = insert("INSERT INTO tag (workspace_id, name) VALUES (?, 'Company Visibility Tag')", workspaceId);
        for (int i = 0; i < owners.length; i++) {
            int company = insert("INSERT INTO company (workspace_id, owner_id, name, website, industry, created_at) "
                + "VALUES (?, ?, ?, ?, ?, '2026-08-01')", owners[i], i % 2 == 0 ? userId : null,
                names[i], "company-" + i + ".example.com", i == 1 ? null : "Industry " + i);
            companyIds.add(company);
            controlJdbc.update("INSERT INTO company_tag (company_id, tag_id) VALUES (?, ?)", company, reportTagId);
            if (List.of(1, 3, 4).contains(i)) {
                controlJdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    company, workspaceId, userId);
            }
        }
        for (int i = 0; i < owners.length; i++) {
            int person = insert("INSERT INTO person (workspace_id, company_id, name) VALUES (?, ?, ?)",
                owners[i], companyIds.getFirst(), "Company Evidence Person " + i);
            personIds.add(person);
            if (List.of(1, 3, 4).contains(i)) {
                controlJdbc.update("INSERT INTO person_share (person_id, workspace_id, granted_by) VALUES (?, ?, ?)",
                    person, workspaceId, userId);
            }
            int deal = addReportDeal(companyIds.get(i), "Company Evidence Deal " + i, "10", "USD");
            dealIds.add(deal);
            addActivity(workspaceId, userId, person, deal, "2026-09-30 12:00:00");
            addNote(workspaceId, userId, person, deal,
                "workspace", "Company Evidence Note " + i, "Evidence", "2026-09-30 12:00:00");
            controlJdbc.update("INSERT INTO task (workspace_id, person_id, deal_id, description, created_at) "
                + "VALUES (?, ?, ?, ?, '2026-09-30 12:00:00')", workspaceId, person, deal,
                "Company Evidence Task " + i);
        }
        int sharedAccountDeal = addReportDeal(companyIds.get(1), "Company Evidence Shared Account", "10", "USD");
        dealIds.add(sharedAccountDeal);
        for (int person : personIds) {
            addActivity(workspaceId, userId, person, sharedAccountDeal, "2026-09-30 12:00:00");
            addNote(workspaceId, userId, person, sharedAccountDeal,
                "workspace", "Deal-attributed company note", "Evidence", "2026-09-30 12:00:00");
            controlJdbc.update("INSERT INTO task (workspace_id, person_id, deal_id, description, created_at) "
                + "VALUES (?, ?, ?, 'Deal-attributed company task', '2026-09-30 12:00:00')",
                workspaceId, person, sharedAccountDeal);
        }
        addActivity(workspaceId, userId, null, sharedAccountDeal, "2026-09-30 12:00:00");
        addNote(workspaceId, userId, null, sharedAccountDeal,
            "workspace", "Deal-only company note", "Evidence", "2026-09-30 12:00:00");
        controlJdbc.update("INSERT INTO task (workspace_id, deal_id, description, created_at) "
            + "VALUES (?, ?, 'Deal-only company task', '2026-09-30 12:00:00')", workspaceId, sharedAccountDeal);
        controlJdbc.update("UPDATE company SET archived_at = '2026-08-03' WHERE id = ?", companyIds.get(5));
        controlJdbc.update("UPDATE person SET archived_at = '2026-08-03' WHERE id = ?", personIds.get(5));
        addNote(workspaceId, userId, personIds.getFirst(), null, "private", "Company Private Note", "Private", "2026-09-30 12:00:00");
    }

    private void seedReports() throws SQLException {
        int[] owners = {workspaceId, workspaceIds.get(1), workspaceIds.get(2), workspaceIds.get(3),
            workspaceIds.get(4), workspaceId};
        String[] names = {"A Owned", "B Shared", "0 Hidden Sibling", "0 Hidden Foreign", "C Winding", "D Archived"};
        LocalDateTime created = LocalDateTime.of(2026, 8, 1, 0, 0);
        pipelineId = insert("INSERT INTO pipeline (workspace_id, name) VALUES (?, 'Report Pipeline')", workspaceId);
        reportStageId = insert("INSERT INTO stage (workspace_id, pipeline_id, name, position) "
            + "VALUES (?, ?, 'Report Open', 0)", workspaceId, pipelineId);
        reportTagId = insert("INSERT INTO tag (workspace_id, name) VALUES (?, 'Report Tag')", workspaceId);
        for (int i = 0; i < owners.length; i++) {
            int company = insert("INSERT INTO company (workspace_id, name, created_at) VALUES (?, ?, ?)",
                owners[i], names[i], created);
            companyIds.add(company);
            int person = addReportPerson(owners[i], company, "Report Person " + i, created);
            personIds.add(person);
            int evidence = addReportPerson(owners[i], companyIds.getFirst(), "Report Evidence " + i, created);
            reportEvidencePersonIds.add(evidence);
            if (List.of(1, 3, 4).contains(i)) {
                controlJdbc.update("INSERT INTO company_share (company_id, workspace_id, granted_by, created_at) "
                    + "VALUES (?, ?, ?, '2026-08-02 00:00:00')", company, workspaceId, userId);
                for (int sharedPerson : List.of(person, evidence)) {
                    controlJdbc.update("INSERT INTO person_share (person_id, workspace_id, granted_by, created_at) "
                        + "VALUES (?, ?, ?, '2026-08-02 00:00:00')", sharedPerson, workspaceId, userId);
                }
            }
            int deal = addReportDeal(company, "Report Deal " + i, Integer.toString((i + 1) * 10), "USD");
            dealIds.add(deal);
            controlJdbc.update("INSERT INTO deal_person (deal_id, person_id) VALUES (?, ?)", deal, person);
            controlJdbc.update("INSERT INTO company_tag (company_id, tag_id) VALUES (?, ?)", company, reportTagId);
            controlJdbc.update("INSERT INTO deal_tag (deal_id, tag_id) VALUES (?, ?)", deal, reportTagId);
        }
        controlJdbc.update("UPDATE company SET archived_at = '2026-08-03' WHERE id = ?", companyIds.get(5));
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

    private String fixtureRows(String table) {
        return switch (table) {
            case "company_tag" -> "company_id IN (SELECT id FROM company WHERE workspace_id IN (" + workspaceIdsSql() + "))";
            case "deal_person", "deal_tag" -> "deal_id IN (SELECT id FROM deal WHERE workspace_id IN (" + workspaceIdsSql() + "))";
            default -> "workspace_id IN (" + workspaceIdsSql() + ")";
        };
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
                    controlJdbc.update("DELETE FROM " + table + " WHERE " + fixtureRows(table));
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
