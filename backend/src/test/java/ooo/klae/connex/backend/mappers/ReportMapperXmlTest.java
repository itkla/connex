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

import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportOffsetSegment;

/** Verifies the report mapper XML and every dynamic aggregate branch can be resolved. */
class ReportMapperXmlTest {

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
        assertWorkspaceScoped(configuration, "aggregateCoverageGaps",
                query("coverage_gap_count", "none"));
        assertWorkspaceScoped(configuration, "aggregateCoverageGaps",
                query("coverage_gap_open_pipeline_value", "company"));
        assertWorkspaceScoped(configuration, "aggregateSingleThreadedDeals",
                query("single_threaded_deal_count", "none"));
        assertWorkspaceScoped(configuration, "aggregateSingleThreadedDeals",
                query("single_threaded_deal_value", "deal"));
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

    private static void assertWorkspaceScoped(
            Configuration configuration, String statement, ReportAggregateQuery query) {
        String sql = configuration.getMappedStatement(ReportMapper.class.getName() + "." + statement)
                .getBoundSql(Map.of("query", query)).getSql();
        assertNotNull(sql);
        assertTrue(sql.contains("workspace_id = ?"));
    }

    private static void assertForecastScoped(
            Configuration configuration, ReportAggregateQuery query) {
        String sql = forecastSql(configuration, query);
        assertTrue(sql.contains("LEFT JOIN pipeline p"));
        assertTrue(sql.contains("LEFT JOIN stage s"));
        assertTrue(sql.contains("p.workspace_id = ?"));
        assertTrue(sql.contains("s.workspace_id = ?"));
        assertTrue(sql.contains("c.workspace_id = ?"));
        assertTrue(sql.contains("historical_deal.workspace_id = ?"));
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
                java.util.List.of(new ReportOffsetSegment(
                        LocalDateTime.of(2026, 1, 1, 0, 0),
                        LocalDateTime.of(2026, 2, 1, 0, 0),
                        0)));
    }
}
