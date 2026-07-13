package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.ReportSchedule;

/** Verifies report-schedule SQL parses, remains plane-clean, and preserves tenant boundaries. */
class ScheduleMapperXmlTest {

    @Test
    void migrationMakesReportAndScheduleScopeStructural() throws Exception {
        String resource = "db/migration/tenant/V70__report_schedule.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("UNIQUE KEY uq_report_schedule_workspace_id (workspace_id, id)"));
            assertTrue(sql.contains("UNIQUE KEY uq_report_schedule_workspace_report (workspace_id, report_definition_id)"));
            assertTrue(sql.contains("INDEX idx_report_schedule_due (enabled, next_run_at)"));
            assertFalse(sql.contains("FOREIGN KEY (run_as_user_id)"));
            assertFalse(sql.contains("FOREIGN KEY (created_by)"));
        }
    }

    @Test
    void mapperXmlParsesAndOnlyDueEnumerationOmitsWorkspacePredicate() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ReportSchedule", ReportSchedule.class);
        String resource = "mappers/ScheduleMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        Map<String, Object> reportParameters = Map.of("workspaceId", 7, "reportDefinitionId", 11);
        Map<String, Object> idParameters = Map.of("workspaceId", 7, "id", 13);
        assertScoped(configuration, "getByReport", reportParameters);
        assertScoped(configuration, "getById", idParameters);
        assertScoped(configuration, "lockById", idParameters);
        assertScoped(configuration, "deleteByReport", reportParameters);

        ReportSchedule schedule = schedule();
        assertScoped(configuration, "insert", schedule);
        assertScoped(configuration, "update", schedule);

        Map<String, Object> claimParameters = new HashMap<>(idParameters);
        claimParameters.put("nextRunAt", LocalDateTime.of(2026, 7, 20, 9, 0));
        claimParameters.put("lastRunAt", LocalDateTime.of(2026, 7, 13, 9, 0));
        assertScoped(configuration, "markClaimed", claimParameters);
        assertScoped(configuration, "markSkipped", claimParameters);

        MappedStatement due = mapped(configuration, "dueScheduleRefs");
        String dueSql = due.getBoundSql(Map.of("now", LocalDateTime.of(2026, 7, 13, 9, 0))).getSql();
        assertTrue(dueSql.contains("enabled = TRUE"));
        assertTrue(dueSql.contains("next_run_at <= ?"));
        assertFalse(dueSql.contains("workspace_id ="));

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(xml.contains("${"));
            assertFalse(xml.contains("app_user"));
            assertFalse(xml.contains("workspace_member"));
        }
    }

    private static ReportSchedule schedule() {
        ReportSchedule schedule = new ReportSchedule();
        schedule.setId(13);
        schedule.setWorkspaceId(7);
        schedule.setReportDefinitionId(11);
        schedule.setCadence("weekly");
        schedule.setRecipientUserIds("[17]");
        schedule.setTimezone("UTC");
        schedule.setHourOfDay(9);
        schedule.setEnabled(true);
        schedule.setRunAsUserId(17);
        schedule.setNextRunAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        schedule.setCreatedBy(17);
        return schedule;
    }

    private static void assertScoped(Configuration configuration, String statement, Object parameters) {
        String sql = mapped(configuration, statement).getBoundSql(parameters).getSql();
        assertTrue(sql.contains("workspace_id"));
        assertTrue(sql.contains("?"));
    }

    private static MappedStatement mapped(Configuration configuration, String statement) {
        return configuration.getMappedStatement(ScheduleMapper.class.getName() + "." + statement);
    }
}
