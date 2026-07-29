package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.BoardPositionUpdate;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;

/** Verifies every deal member-scope SQL branch resolves to the canonical owner predicate. */
class DealMapperXmlTest {
    private static final List<String> SCOPED_STATEMENTS = List.of(
        "getDealsPageFiltered",
        "countDealsFiltered",
        "getDealsFiltered",
        "dealMetricsFiltered",
        "getFilteredDealIds"
    );
    private static final List<String> ANALYTICS_SCOPED_STATEMENTS = List.of(
        "dealKpiWindow",
        "dealKpiClosedSeriesByBoundaries",
        "dealKpiNewPipelineSeriesByBoundaries",
        "dealPipelineValueWindow",
        "revenueClosedByPeriods",
        "revenueScheduledClosedByPeriods",
        "revenueProjectedByPeriods"
    );

    @Test
    void memberScopeBranchesKeepWorkspaceFirstAndRenderExpectedPredicates() throws Exception {
        Configuration configuration = configuration();
        MemberScope allTeam = MemberScope.fromRequest(null, null, 7);
        MemberScope me = MemberScope.fromRequest("me", List.of(99), 7);
        MemberScope members = MemberScope.fromRequest("members", List.of(3, 5), 7);
        MemberScope unassigned = MemberScope.fromRequest("unassigned", List.of(99), 7);

        for (String statement : java.util.stream.Stream.concat(
                SCOPED_STATEMENTS.stream(), ANALYTICS_SCOPED_STATEMENTS.stream()).toList()) {
            assertScopePredicate(configuration, statement, allTeam, null);
            assertScopePredicate(configuration, statement, me, "d.owner_id = ?");
            assertScopePredicate(configuration, statement, members, "d.owner_id IN");
            assertScopePredicate(configuration, statement, unassigned, "d.owner_id IS NULL");
        }

        String membersSql = sql(configuration, "getDealsPageFiltered", members);
        assertTrue(membersSql.matches(".*d\\.owner_id IN \\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"));
        String singleMemberSql = sql(configuration, "getDealsPageFiltered",
            MemberScope.fromRequest("members", List.of(3), 7));
        assertTrue(singleMemberSql.matches(".*d\\.owner_id IN \\(\\s*\\?\\s*\\).*"));
    }

    @Test
    void lockedDealLookupUsesTheCompositeForeignKeyIndex() throws Exception {
        String sql = sql(configuration(), "getDealByIdForUpdate", MemberScope.allTeam());

        assertTrue(sql.contains("FROM deal FORCE INDEX (uq_deal_workspace_id)"));
        assertTrue(sql.contains("WHERE workspace_id = ? AND id = ?"));
        assertTrue(sql.endsWith("FOR UPDATE"));
    }

    @Test
    void batchPositionUpdateKeepsWorkspaceAndStagePredicates() throws Exception {
        String sql = sql(configuration(), "setPositions", MemberScope.allTeam());

        assertTrue(sql.startsWith("UPDATE deal SET position = CASE id"));
        assertTrue(sql.contains("WHEN ? THEN ? WHEN ? THEN ? ELSE position END"));
        assertTrue(sql.contains("WHERE workspace_id = ? AND stage_id = ?"));
        assertTrue(sql.endsWith("AND id IN ( ? , ? )"));
    }

    @Test
    void filteredQueriesJoinBoundSegmentMembershipOnlyWhenSupplied() throws Exception {
        Configuration configuration = configuration();

        for (String statement : SCOPED_STATEMENTS) {
            String unsegmented = sql(configuration, statement, MemberScope.allTeam());
            String segmented = sql(configuration, statement, MemberScope.allTeam(), "[17,23]");

            assertFalse(unsegmented.contains("JSON_TABLE"));
            assertTrue(segmented.contains(
                "JOIN JSON_TABLE(?, '$[*]' COLUMNS(id INT PATH '$')) segment_ids ON segment_ids.id = d.id"));
            assertTrue(segmented.indexOf("segment_ids.id = d.id") < segmented.indexOf("d.workspace_id = ?"));
        }
    }

    @Test
    void analyticsPeriodQueriesUseBoundaryJoinsAndGroupedAggregation() throws Exception {
        Configuration configuration = configuration();

        for (String statement : List.of(
                "dealKpiClosedSeriesByBoundaries",
                "dealKpiNewPipelineSeriesByBoundaries",
                "revenueClosedByPeriods",
                "revenueScheduledClosedByPeriods",
                "revenueProjectedByPeriods")) {
            String sql = sql(configuration, statement, MemberScope.allTeam());

            assertTrue(sql.contains("JOIN deal d"));
            assertTrue(sql.contains("GROUP BY boundary.bucket_index"));
            assertFalse(sql.contains("( SELECT SUM("));
        }
    }

    private static void assertScopePredicate(Configuration configuration, String statement,
            MemberScope scope, String predicate) {
        String sql = sql(configuration, statement, scope);
        int workspaceIndex = sql.indexOf("d.workspace_id = ?");
        assertTrue(workspaceIndex >= 0);
        if (predicate == null) {
            assertFalse(sql.contains("d.owner_id = ?"));
            assertFalse(sql.contains("d.owner_id IN"));
            assertFalse(sql.contains("d.owner_id IS NULL"));
            return;
        }
        int predicateIndex = sql.indexOf(predicate);
        assertTrue(predicateIndex > workspaceIndex);
    }

    private static String sql(Configuration configuration, String statement, MemberScope scope) {
        return sql(configuration, statement, scope, null);
    }

    private static String sql(
            Configuration configuration, String statement, MemberScope scope, String segmentIdsJson) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 11);
        parameters.put("segmentIdsJson", segmentIdsJson);
        parameters.put("id", 17);
        parameters.put("pipelineId", 13);
        parameters.put("stageId", 19);
        parameters.put("positions", List.of(
            new BoardPositionUpdate(23, 0),
            new BoardPositionUpdate(29, 1)
        ));
        parameters.put("memberScope", scope);
        parameters.put("currency", null);
        parameters.put("startUtc", LocalDateTime.of(2026, 1, 1, 0, 0));
        parameters.put("endUtc", LocalDateTime.of(2026, 1, 2, 0, 0));
        parameters.put("startDate", LocalDate.of(2026, 1, 1));
        parameters.put("endDate", LocalDate.of(2026, 1, 2));
        parameters.put("periods", List.of(new AnalyticsPeriod(
            0,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 2, 0, 0))));
        parameters.put("noCompany", false);
        parameters.put("limit", 25);
        parameters.put("offset", 0);
        return configuration.getMappedStatement(DealMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of("mappers/PersonMapper.xml", "mappers/DealMapper.xml")) {
            try (InputStream input = DealMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
}
