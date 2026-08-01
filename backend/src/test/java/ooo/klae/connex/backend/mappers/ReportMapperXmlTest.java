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

        String retention = configuration.getMappedStatement(
                        ReportMapper.class.getName() + ".deleteScheduledSnapshotsBeyondRetention")
                .getBoundSql(Map.of("workspaceId", 7, "reportScheduleId", 11, "keepCount", 25))
                .getSql();
        assertTrue(retention.contains("workspace_id = ?"));
        assertTrue(retention.contains("report_schedule_id = ?"));
        assertTrue(retention.contains("origin = 'scheduled'"));
        assertTrue(retention.contains("SELECT id FROM ("));
        assertTrue(retention.contains("LIMIT ?"));
        assertFalse(retention.contains("${"));
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
        return new ReportAggregateQuery(
                7, measure, groupBy, "month",
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
