package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ReportGoal;

/** Verifies every report-goal mapper statement parses and remains workspace scoped. */
class GoalMapperXmlTest {

    @Test
    void migrationMakesWorkspaceScopeUniquenessStructural() throws Exception {
        String resource = "db/migration/tenant/V69__report_goal.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("owner_scope_id INT GENERATED ALWAYS AS (COALESCE(owner_id, 0)) STORED"));
            assertTrue(sql.contains("UNIQUE KEY uq_report_goal_effective_scope_period"));
        }
    }

    @Test
    void mapperXmlParsesAndScopesEveryStatement() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ReportGoal", ReportGoal.class);
        String resource = "mappers/GoalMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> listParameters = new HashMap<>();
        listParameters.put("workspaceId", 7);
        Map<String, Object> getParameters = new HashMap<>(listParameters);
        getParameters.put("id", 11);
        Map<String, Object> periodParameters = new HashMap<>(listParameters);
        periodParameters.put("metric", "won_revenue");
        periodParameters.put("periodType", "month");
        periodParameters.put("periodStart", LocalDate.of(2026, 7, 1));
        periodParameters.put("currency", "USD");

        assertScoped(configuration, "getGoals", listParameters);
        assertScoped(configuration, "getGoal", getParameters);
        assertScoped(configuration, "getGoalsForPeriod", periodParameters);
        assertScoped(configuration, "delete", getParameters);

        ReportGoal goal = new ReportGoal();
        goal.setId(11);
        goal.setWorkspaceId(7);
        goal.setMetric("won_revenue");
        goal.setPeriodType("month");
        goal.setPeriodStart(LocalDate.of(2026, 7, 1));
        goal.setTargetValue(new java.math.BigDecimal("1000.00"));
        goal.setCurrency("USD");
        assertScoped(configuration, "insert", goal);
        assertScoped(configuration, "update", goal);

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(xml.contains("${"));
            assertFalse(xml.contains("app_user"));
            assertFalse(xml.contains("workspace_member"));
        }
    }

    private static void assertScoped(Configuration configuration, String statement, Object parameters) {
        MappedStatement mapped = configuration.getMappedStatement(GoalMapper.class.getName() + "." + statement);
        String sql = mapped.getBoundSql(parameters).getSql();
        assertNotNull(sql);
        assertTrue(sql.contains("workspace_id"));
        assertTrue(sql.contains("?"));
    }
}
