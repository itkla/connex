package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.PersonEdge;
import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel;

/** Verifies the report mapper XML and every dynamic aggregate branch can be resolved. */
class ReportMapperXmlTest {

    @Test
    void networkReportSourceQueriesAreBoundedBeforeMaterialization() throws Exception {
        String people = resourceText("mappers/PersonMapper.xml");
        assertTrue(people.contains("id=\"getPersonsForNetworkReport\""));
        assertTrue(people.contains("LIMIT #{limit}"));

        String edges = resourceText("mappers/PersonEdgeMapper.xml");
        assertTrue(edges.contains("id=\"getEdgesForNetworkReport\""));
        assertTrue(edges.contains("id=\"getEdgesForReverseIntroReport\""));
        assertTrue(edges.contains("collection=\"personIds\""));
        assertTrue(edges.contains("LIMIT #{limit}"));

        String introductions = resourceText("mappers/IntroductionMapper.xml");
        assertTrue(introductions.contains("id=\"findCandidatePersonsForReport\""));
        assertTrue(introductions.contains("id=\"findWorkspaceEmploymentForReport\""));
        assertTrue(introductions.contains("id=\"findExistingPairsForReport\""));
        assertTrue(introductions.contains("id=\"findExistingPairsForReverseIntroReport\""));
        assertTrue(introductions.contains("collection=\"personIds\""));
        assertTrue(introductions.contains("LIMIT #{limit}"));
        assertFalse(people.contains("${"));
        assertFalse(edges.contains("${"));
        assertFalse(introductions.contains("${"));
    }

    @Test
    void boundedNetworkSourceStatementsParseAndBindLimits() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("Person", Person.class);
        configuration.getTypeAliasRegistry().registerAlias("Company", Company.class);
        configuration.getTypeAliasRegistry().registerAlias("Tag", Tag.class);
        configuration.getTypeAliasRegistry().registerAlias("PersonEdge", PersonEdge.class);
        for (String resource : new String[] {
                "mappers/PersonMapper.xml",
                "mappers/PersonEdgeMapper.xml",
                "mappers/IntroductionMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        String peopleSql = configuration.getMappedStatement(
                        PersonMapper.class.getName() + ".getPersonsForNetworkReport")
                .getBoundSql(Map.of("workspaceId", 7, "limit", 101))
                .getSql();
        assertTrue(peopleSql.contains("LIMIT ?"));
        String edgeSql = configuration.getMappedStatement(
                        PersonEdgeMapper.class.getName() + ".getEdgesForReverseIntroReport")
                .getBoundSql(Map.of(
                        "workspaceId", 7,
                        "orgWorkspaceIdsJson", "[7]",
                        "personIds", java.util.List.of(1, 2),
                        "limit", 101))
                .getSql();
        assertTrue(edgeSql.contains("e.workspace_id = ?"));
        assertTrue(edgeSql.contains("e.source_person_id IN"));
        assertTrue(edgeSql.contains("LIMIT ?"));
        for (String statement : new String[] {
                "findCandidatePersonsForReport",
                "findWorkspaceEmploymentForReport",
                "findExistingPairsForReport",
                "findExistingPairsForReverseIntroReport"}) {
            Map<String, Object> parameters = statement.equals("findCandidatePersonsForReport")
                    || statement.equals("findExistingPairsForReport")
                    ? Map.of("workspaceId", 7, "limit", 101)
                    : Map.of("workspaceId", 7, "personIds", java.util.List.of(1, 2), "limit", 101);
            String sql = configuration.getMappedStatement(IntroductionMapper.class.getName() + "." + statement)
                    .getBoundSql(parameters)
                    .getSql();
            assertTrue(sql.contains("workspace_id = ?"));
            assertTrue(sql.contains("LIMIT ?"));
        }
    }

    @Test
    void conversionMigrationKeepsAmbiguousLegacyHistoryFailClosed() throws Exception {
        String resource = "db/migration/tenant/V71__deal_stage_history_conversion.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("conversion_eligible BOOLEAN NOT NULL DEFAULT FALSE"));
            assertFalse(sql.contains("UPDATE deal_stage_history"));
            assertTrue(sql.indexOf("ALTER TABLE deal_stage_history")
                == sql.lastIndexOf("ALTER TABLE deal_stage_history"));
            assertTrue(sql.contains("workspace_id, conversion_eligible, stage_id, deal_id"));
        }
    }

    @Test
    void conversionSeedUsesOnlyLatestCurrentStageOfOpenDeals() throws Exception {
        String resource = "db/migration/tenant/V72__seed_open_deal_conversion_history.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("MAX(stage_event.id) AS history_id"));
            assertTrue(sql.contains("stage_event.workspace_id = open_deal.workspace_id"));
            assertTrue(sql.contains("stage_event.deal_id = open_deal.id"));
            assertTrue(sql.contains("stage_event.stage_id = open_deal.stage_id"));
            assertTrue(sql.contains("open_deal.won IS NULL"));
            assertTrue(sql.contains("SET eligible_history.conversion_eligible = TRUE"));
        }
    }

    @Test
    void coverageWarmthUsesBoundModelAndAlignedReference() throws Exception {
        String mapper = resourceText("mappers/ReportMapper.xml");
        int start = mapper.indexOf("<select id=\"aggregateCoverageGaps\"");
        int end = mapper.indexOf("<select id=\"aggregateSingleThreadedDeals\"", start);
        String coverage = mapper.substring(start, end);

        assertTrue(coverage.contains("#{warmthReference}"));
        assertTrue(coverage.contains("#{model.decayBase}"));
        assertTrue(coverage.contains("#{model.halfLifeDays}"));
        assertTrue(coverage.contains("#{model.warmMinimumRawWeight}"));
        assertFalse(coverage.contains("ROUND(100.0"));
        assertFalse(coverage.contains("/ 30.0"));
        assertFalse(coverage.contains("/ 0.7"));
    }

    @Test
    void mapperXmlParsesAndBuildsAggregateStatements() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ReportDefinition", ReportDefinition.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportSnapshot", ReportSnapshot.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportAggregateRow", ReportAggregateRow.class);
        String resource = "mappers/ReportMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        ReportAggregateQuery query = query("won_revenue", "date");
        for (String statement : new String[] {
                "aggregateDeals", "aggregateActivities", "aggregateTasks",
                "aggregatePeople", "aggregateCompanies"}) {
            String sql = configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                    .getBoundSql(Map.of("query", query)).getSql();
            assertNotNull(sql);
            assertTrue(sql.contains("workspace_id = ?"));
        }
        for (String measure : new String[] {
                "employment_departure_count", "employment_arrival_count"}) {
            for (String group : new String[] {"none", "date", "company", "person"}) {
                String sql = configuration.getMappedStatement(
                                ReportMapper.class.getName() + ".aggregateEmployment")
                        .getBoundSql(Map.of("query", query(measure, group)))
                        .getSql();
                assertTrue(sql.contains("employment.workspace_id = ?"));
                assertTrue(sql.contains("person.workspace_id = ?"));
                assertTrue(sql.contains("person.suspended_at IS NULL"));
                assertFalse(sql.contains("${"));
            }
        }
        for (String measure : new String[] {
                "lead_count", "qualified_count", "converted_count", "disqualified_count",
                "qualification_rate", "conversion_rate", "time_to_convert_days",
                "first_response_hours", "first_response_breach_rate"}) {
            for (String group : new String[] {"none", "date", "owner", "lead_source"}) {
                String sql = configuration.getMappedStatement(
                                ReportMapper.class.getName() + ".aggregateLeadLifecycle")
                        .getBoundSql(Map.of("query", query(measure, group)))
                        .getSql();
                assertTrue(sql.contains("person.workspace_id = ?"), measure + "/" + group);
                assertTrue(sql.contains("person.archived_at IS NULL"), measure + "/" + group);
                assertTrue(sql.contains("person.suspended_at IS NULL"), measure + "/" + group);
                assertFalse(sql.contains("${"), measure + "/" + group);
                assertFalse(sql.contains("JOIN `user`"),
                    "reports must not join the control-plane user table: " + measure);
            }
        }

        String filteredEmployment = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".aggregateEmployment")
                .getBoundSql(Map.of("query", query(
                        "employment_arrival_count", "person",
                        null, java.util.List.of(2), null, java.util.List.of(3))))
                .getSql();
        assertTrue(filteredEmployment.contains("person.owner_id IN"));
        assertTrue(filteredEmployment.contains("employment_tag.workspace_id = ?"));
        assertTrue(filteredEmployment.contains("prior_employment.workspace_id = ?"));
        assertTrue(filteredEmployment.contains("prior_employment.person_id = employment.person_id"));
        assertTrue(filteredEmployment.contains("prior_employment.id <> employment.id"));
        assertTrue(filteredEmployment.contains("prior_employment.ended_at <= employment.started_at"));
        assertWorkspaceScoped(configuration, "aggregateCoverageGaps",
                query("coverage_gap_count", "none"));
        assertWorkspaceScoped(configuration, "aggregateCoverageGaps",
                query("coverage_gap_open_pipeline_value", "company"));
        assertWorkspaceScoped(configuration, "aggregateSingleThreadedDeals",
                query("single_threaded_deal_count", "none"));
        assertWorkspaceScoped(configuration, "aggregateSingleThreadedDeals",
                query("single_threaded_deal_value", "deal"));
        ReportAggregateQuery networkQuery = query(
                "warm_intro_opportunity_value", "company",
                java.util.List.of(1), java.util.List.of(2), java.util.List.of("open"), java.util.List.of(3));
        String networkSql = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".getNetworkAccountValues")
                .getBoundSql(Map.of("query", networkQuery, "limit", 101))
                .getSql();
        assertTrue(networkSql.contains("network_deal.workspace_id = ?"));
        assertTrue(networkSql.contains("network_deal.won IS NULL"));
        assertTrue(networkSql.contains("SUM(GREATEST(network_deal.value, 0))"));
        assertTrue(networkSql.contains("network_company.workspace_id = ?"));
        assertTrue(networkSql.contains("FROM company_share network_company_share"));
        assertTrue(networkSql.contains("ows.org_id = vws.org_id"));
        assertTrue(networkSql.contains("network_deal.pipeline_id IN"));
        assertTrue(networkSql.contains("network_deal.owner_id IN"));
        assertTrue(networkSql.contains("AND 'open' IN"));
        assertTrue(networkSql.contains("FROM company_tag network_company_tag"));
        assertTrue(networkSql.contains("LIMIT ?"));
        for (String measure : new String[] {"forecast_best", "forecast_weighted", "forecast_worst"}) {
            for (String group : new String[] {"none", "date", "pipeline", "stage"}) {
                assertForecastScoped(configuration, query(measure, group));
            }
        }
        String filteredForecast = forecastSql(configuration, filteredForecastQuery());
        assertTrue(filteredForecast.contains("d.pipeline_id IN"));
        assertTrue(filteredForecast.contains("d.owner_id IN"));
        assertTrue(filteredForecast.contains("CASE WHEN d.won = TRUE"));
        assertTrue(filteredForecast.contains("FROM deal_tag"));
        assertTrue(filteredForecast.contains("FROM deal_stage_history stage_event"));
        assertTrue(filteredForecast.contains("COUNT(DISTINCT stage_event.deal_id)"));
        assertTrue(filteredForecast.contains("reached_stage_history.won_count"));
        for (String statement : new String[] {"getVisiblePersonIdsAt", "getVisibleCompanyIdsAt"}) {
            assertNotNull(configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                    .getBoundSql(Map.of(
                            "workspaceId", 7,
                            "asOf", LocalDateTime.of(2026, 2, 1, 0, 0)))
                    .getSql());
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            assertFalse(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("${"));
        }
    }

    @Test
    void documentAggregatesBindWorkspaceAcrossEveryBranch() throws Exception {
        Configuration configuration = reportMapperConfiguration();
        for (String bucket : new String[] {"day", "week", "month"}) {
            for (String group : new String[] {"none", "date", "owner", "company"}) {
                for (String measure : new String[] {"quote_count", "quote_issue_rate"}) {
                    String sql = aggregateSql(
                            configuration, "aggregateDocuments", measure, group, bucket);
                    assertTrue(sql.contains("dd.workspace_id = ?"));
                    assertTrue(sql.contains("d.workspace_id = ?"));
                    assertTrue(sql.contains("c.workspace_id = ?"));
                    assertTrue(sql.contains("dd.type = 'quote'"));
                    assertTrue(sql.contains("dd.generated_at >= ?"));
                    assertTrue(sql.contains("dd.generated_at < ?"));
                    assertFalse(sql.contains("${"));
                }
                String outcomes = aggregateSql(
                        configuration, "aggregateDocumentOutcomes", "document_to_win_rate", group, bucket);
                assertTrue(outcomes.contains("d.workspace_id = ?"));
                assertTrue(outcomes.contains("c.workspace_id = ?"));
                assertTrue(outcomes.contains("FROM deal_document WHERE workspace_id = ?"));
                assertTrue(outcomes.contains("MIN(generated_at) AS first_generated_at"));
                assertTrue(outcomes.contains("first_document.first_generated_at >= ?"));
                assertTrue(outcomes.contains("first_document.first_generated_at < ?"));
                assertFalse(outcomes.contains("${"));
                for (String measure : new String[] {"approval_decision_count", "approval_cycle_days"}) {
                    String sql = aggregateSql(
                            configuration, "aggregateDocumentApprovals", measure, group, bucket);
                    assertTrue(sql.contains("a.workspace_id = ?"));
                    assertTrue(sql.contains("d.workspace_id = ?"));
                    assertTrue(sql.contains("c.workspace_id = ?"));
                    assertTrue(sql.contains("a.status IN ('approved', 'rejected')"));
                    assertTrue(sql.contains("a.decided_at >= ?"));
                    assertTrue(sql.contains("a.decided_at < ?"));
                    assertFalse(sql.contains("${"));
                }
            }
        }
    }

    /**
     * The delivery lifecycle moves a finalized quote through {@code sent} and {@code signed}, so the
     * issued numerator must accept all three terminal-or-later statuses and fall back to workspace-
     * scoped delivery evidence for a quote that was superseded after it was issued.
     */
    @Test
    void quoteIssueRateCountsDeliveredSignedAndDeliveredSupersededQuotes() throws Exception {
        Configuration configuration = reportMapperConfiguration();
        for (String group : new String[] {"none", "date", "owner", "company"}) {
            String sql = aggregateSql(
                    configuration, "aggregateDocuments", "quote_issue_rate", group, "day");
            assertTrue(sql.contains("dd.status IN ('final', 'sent', 'signed')"));
            assertTrue(sql.contains("dd.status = 'superseded'"));
            assertTrue(sql.contains("FROM document_delivery issued_delivery"));
            assertTrue(sql.contains("issued_delivery.workspace_id = ?"));
            assertTrue(sql.contains("issued_delivery.document_id = dd.id"));
            assertFalse(sql.contains("${"));
        }
        assertFalse(aggregateSql(configuration, "aggregateDocuments", "quote_count", "none", "day")
                .contains("document_delivery"));
    }

    /**
     * The discount aggregate joins {@code deal_line_item}, which carries its own {@code unit}
     * column. MySQL resolves a bare {@code unit} in {@code GROUP BY} to that column rather than to
     * the {@code 'percent' AS unit} select alias, which would split one discount figure into one
     * row per line-item unit. Grouping by the {@code group_key}/{@code group_label} aliases keeps
     * the currency partition — already encoded in {@code group_key} — without naming a column that
     * a joined table can shadow.
     */
    @Test
    void dealDiscountAggregateBindsWorkspaceLineItemsAndCurrencyPartition() throws Exception {
        Configuration configuration = reportMapperConfiguration();
        for (String measure : new String[] {"effective_discount_percent", "open_discount_percent"}) {
            for (String group : new String[] {"none", "date", "pipeline", "stage", "owner", "company"}) {
                String sql = aggregateSql(configuration, "aggregateDealDiscount", measure, group, "month");
                assertTrue(sql.contains("d.workspace_id = ?"));
                assertTrue(sql.contains("li.workspace_id = ?"));
                assertTrue(sql.contains("li.deal_id = d.id"));
                assertTrue(sql.contains("p.workspace_id = ?"));
                assertTrue(sql.contains("s.workspace_id = ?"));
                assertTrue(sql.contains("c.workspace_id = ?"));
                assertTrue(sql.contains("CONCAT(COALESCE(d.currency, ''), ':',"));
                assertTrue(sql.contains("NULLIF(SUM(li.unit_price * li.quantity), 0)"));
                assertTrue(sql.contains("HAVING SUM(li.unit_price * li.quantity) > 0"));
                assertTrue(sql.contains("GROUP BY group_key, group_label"));
                assertFalse(sql.contains("GROUP BY group_key, group_label, unit"));
                assertFalse(sql.contains("${"));
            }
        }
        String won = aggregateSql(
                configuration, "aggregateDealDiscount", "effective_discount_percent", "none", "month");
        assertTrue(won.contains("d.won = TRUE"));
        assertTrue(won.contains("d.closed_at >= ?"));
        assertTrue(won.contains("d.closed_at < ?"));
        String open = aggregateSql(
                configuration, "aggregateDealDiscount", "open_discount_percent", "none", "month");
        assertTrue(open.contains("d.won IS NULL"));
        assertTrue(open.contains("d.expected_close_date >= ?"));
        assertTrue(open.contains("d.expected_close_date < ?"));
    }

    /**
     * {@code DealValueContractArchTest.revenueStatementsNeverReadDealLineItems} classifies a
     * {@code ReportMapper} statement as revenue SQL when its id, any nested test attribute, or its
     * resolved text mentions {@code revenue}, and forbids such a statement from reading
     * {@code deal_line_item}. The discount aggregate reads line items by design, so a single
     * {@code revenue} token anywhere inside it would fail the build.
     */
    @Test
    void dealDiscountStatementNeverMentionsRevenue() throws Exception {
        String mapper = resourceText("mappers/ReportMapper.xml");
        int start = mapper.indexOf("<select id=\"aggregateDealDiscount\"");
        assertTrue(start >= 0);
        int end = mapper.indexOf("</select>", start);
        assertTrue(end > start);
        String statement = mapper.substring(start, end);

        assertTrue(statement.contains("deal_line_item"));
        assertFalse(statement.toLowerCase(java.util.Locale.ROOT).contains("revenue"));
    }

    @Test
    void dealAggregateStillSelectsClosedAndExpectedCloseCohortsAfterFragmentWidening() throws Exception {
        Configuration configuration = reportMapperConfiguration();
        String won = aggregateSql(configuration, "aggregateDeals", "won_revenue", "date", "month");
        assertTrue(won.contains("d.closed_at >= ?"));
        assertTrue(won.contains("d.closed_at < ?"));
        assertFalse(won.contains("deal_line_item"));
        String open = aggregateSql(
                configuration, "aggregateDeals", "open_pipeline_value", "date", "month");
        assertTrue(open.contains("d.expected_close_date >= ?"));
        assertTrue(open.contains("d.expected_close_date < ?"));
        assertFalse(open.contains("deal_line_item"));
    }

    @Test
    void employmentArrivalMigrationAddsStartedAtRangeIndex() throws Exception {
        String sql = resourceText("db/migration/tenant/V122__person_employment_started_index.sql");

        assertTrue(sql.contains("idx_person_employment_started"));
        assertTrue(sql.contains("workspace_id, started_at, person_id"));
    }

    @Test
    void reportSnapshotOriginMigrationKeepsScheduleForeignKeyNullableAndIndexed() throws Exception {
        String sql = resourceText("db/migration/tenant/V139__report_snapshot_origin.sql");

        assertTrue(sql.contains("ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'manual'"));
        assertTrue(sql.contains("ADD COLUMN report_schedule_id INT NULL"));
        assertTrue(sql.contains("CHECK (origin IN ('manual', 'scheduled'))"));
        assertTrue(sql.contains(
                "idx_report_snapshot_schedule (report_schedule_id, workspace_id, origin, generated_at, id)"));
        assertTrue(sql.contains(
                "FOREIGN KEY (report_schedule_id) REFERENCES report_schedule(id) ON DELETE SET NULL"));
        assertFalse(sql.contains("FOREIGN KEY (workspace_id, report_schedule_id)"));
    }

    @Test
    void snapshotCapAndRetentionStatementsBindTenantScheduleAndOrigin() throws Exception {
        Configuration configuration = reportMapperConfiguration();
        String manualCount = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".countManualSnapshots")
                .getBoundSql(Map.of("workspaceId", 7, "reportDefinitionId", 9))
                .getSql();
        assertTrue(manualCount.contains("workspace_id = ?"));
        assertTrue(manualCount.contains("report_definition_id = ?"));
        assertTrue(manualCount.contains("origin = 'manual'"));

        for (String statement : new String[] {
                "countSnapshots", "countSnapshotsNotGeneratedBy", "countScheduledSnapshots"}) {
            String deletionGuard = configuration.getMappedStatement(
                            ReportMapper.class.getName() + "." + statement)
                    .getBoundSql(Map.of(
                            "workspaceId", 7,
                            "reportDefinitionId", 9,
                            "generatedBy", 13))
                    .getSql();
            assertTrue(deletionGuard.contains("workspace_id = ?"));
            assertTrue(deletionGuard.contains("report_definition_id = ?"));
            assertFalse(deletionGuard.contains("${"));
        }

        String retention = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".deleteScheduledSnapshotsBeyondRetention")
                .getBoundSql(Map.of(
                        "workspaceId", 7,
                        "reportScheduleId", 11,
                        "reportDefinitionId", 9,
                        "keepCount", 25))
                .getSql();
        assertTrue(retention.contains("workspace_id = ?"));
        assertTrue(retention.contains("report_schedule_id = ?"));
        assertTrue(retention.contains("report_schedule_id IS NULL"));
        assertTrue(retention.contains("report_definition_id = ?"));
        assertTrue(retention.contains("origin = 'scheduled'"));
        assertTrue(retention.contains("SELECT id FROM ("));
        assertTrue(retention.contains("LIMIT ?"));

        String capacityPrune = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".deleteOldestScheduledSnapshots")
                .getBoundSql(Map.of("workspaceId", 7, "limit", 1000))
                .getSql();
        assertTrue(capacityPrune.contains("workspace_id = ?"));
        assertTrue(capacityPrune.contains("origin = 'scheduled'"));
        assertTrue(capacityPrune.contains("ORDER BY generated_at ASC, id ASC"));
        assertTrue(capacityPrune.contains("LIMIT ?"));

        String orphanPrune = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".deleteOrphanedScheduledSnapshots")
                .getBoundSql(Map.of("workspaceId", 7, "reportDefinitionId", 9))
                .getSql();
        assertTrue(orphanPrune.contains("workspace_id = ?"));
        assertTrue(orphanPrune.contains("report_definition_id = ?"));
        assertTrue(orphanPrune.contains("report_schedule_id IS NULL"));
        assertTrue(orphanPrune.contains("origin = 'scheduled'"));
        assertFalse(retention.contains("${"));
        assertFalse(capacityPrune.contains("${"));
        assertFalse(orphanPrune.contains("${"));
    }

    private static void assertWorkspaceScoped(
            Configuration configuration, String statement, ReportAggregateQuery query) {
        String sql = configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                .getBoundSql(Map.of(
                    "query", query,
                    "warmthReference", query.endUtc().minusNanos(1_000_000),
                    "model", RelationshipWarmthModel.current().sqlParameters()))
                .getSql();
        assertNotNull(sql);
        assertTrue(sql.contains("workspace_id = ?"));
    }

    private static String resourceText(String resource) throws Exception {
        try (InputStream input = ReportMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Configuration reportMapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ReportDefinition", ReportDefinition.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportSnapshot", ReportSnapshot.class);
        configuration.getTypeAliasRegistry().registerAlias("ReportAggregateRow", ReportAggregateRow.class);
        String resource = "mappers/ReportMapper.xml";
        try (InputStream input = ReportMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static void assertForecastScoped(
            Configuration configuration, ReportAggregateQuery query) {
        String sql = forecastSql(configuration, query);
        assertTrue(sql.contains("LEFT JOIN pipeline p"));
        assertTrue(sql.contains("LEFT JOIN stage s"));
        assertTrue(sql.contains("p.workspace_id = ?"));
        assertTrue(sql.contains("s.workspace_id = ?"));
        assertTrue(sql.contains("c.workspace_id = ?"));
        assertTrue(sql.contains("closed_stage_deal.workspace_id = ?"));
        assertTrue(sql.contains("stage_event.workspace_id = ?"));
        assertTrue(sql.contains("stage_event.conversion_eligible = TRUE"));
        assertTrue(sql.contains("reached_deal.workspace_id = stage_event.workspace_id"));
        assertTrue(sql.contains("reached_deal.workspace_id = ?"));
        assertTrue(sql.contains("workspace_deal.workspace_id = ?"));
        assertTrue(sql.contains("d.workspace_id = ?"));
        assertFalse(sql.contains("${"));
    }

    private static String forecastSql(Configuration configuration, ReportAggregateQuery query) {
        return configuration.getMappedStatement(ReportMapper.class.getName() + ".aggregateForecast")
                .getBoundSql(Map.of("query", query)).getSql();
    }

    /**
     * The bound SQL of a statement built from resolved {@code <include>} fragments, with runs of
     * whitespace collapsed. MyBatis joins each dynamic fragment with a separator space, so an
     * expression that spans an include boundary is only contiguous after normalization.
     */
    private static String aggregateSql(
            Configuration configuration, String statement, String measure, String group, String bucket) {
        String sql = configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                .getBoundSql(Map.of("query", query(measure, group, bucket, null, null, null, null)))
                .getSql();
        assertNotNull(sql);
        return sql.replaceAll("\\s+", " ");
    }

    private static ReportAggregateQuery query(String measure, String groupBy) {
        return query(measure, groupBy, null, null, null, null);
    }

    private static ReportAggregateQuery filteredForecastQuery() {
        return query("forecast_weighted", "stage",
                java.util.List.of(1), java.util.List.of(2), java.util.List.of("open"), java.util.List.of(3));
    }

    private static ReportAggregateQuery query(
            String measure,
            String groupBy,
            java.util.List<Integer> pipelineIds,
            java.util.List<Integer> ownerIds,
            java.util.List<String> statuses,
            java.util.List<Integer> tagIds) {
        return query(measure, groupBy, "month", pipelineIds, ownerIds, statuses, tagIds);
    }

    private static ReportAggregateQuery query(
            String measure,
            String groupBy,
            String bucket,
            java.util.List<Integer> pipelineIds,
            java.util.List<Integer> ownerIds,
            java.util.List<String> statuses,
            java.util.List<Integer> tagIds) {
        return new ReportAggregateQuery(
                7, measure, groupBy, bucket,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                pipelineIds, ownerIds, statuses, tagIds, null,
                new BigDecimal("0.5"),
                10,
                java.util.List.of(new ReportOffsetSegment(
                        LocalDateTime.of(2026, 1, 1, 0, 0),
                        LocalDateTime.of(2026, 2, 1, 0, 0),
                        0)));
    }
}
