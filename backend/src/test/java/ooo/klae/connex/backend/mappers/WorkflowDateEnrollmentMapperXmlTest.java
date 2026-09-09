package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.WorkflowDateScheduleStatusDto;

/** Verifies the date-enrollment mapper resolves its DTO and retains tenant scoping. */
class WorkflowDateEnrollmentMapperXmlTest {

    @Test
    void scheduleStatusUsesQualifiedDtoAndWorkspaceBoundSql() throws Exception {
        Configuration configuration = configuration();
        MappedStatement statement = configuration.getMappedStatement(
            WorkflowDateEnrollmentMapper.class.getName() + ".getScheduleStatus");
        assertEquals(
            WorkflowDateScheduleStatusDto.class,
            statement.getResultMaps().getFirst().getType());
        var bound = statement.getBoundSql(Map.of("workspaceId", 7, "workflowId", 11));
        assertTrue(bound.getSql().contains("workspace_id"));
        assertTrue(bound.getParameterMappings().stream()
            .anyMatch(mapping -> mapping.getProperty().endsWith("workspaceId")));
    }

    @Test
    void dueSelectionExcludesInactivePausedAndObsoleteWorkflowGenerations() throws Exception {
        MappedStatement statement = configuration().getMappedStatement(
            WorkflowDateEnrollmentMapper.class.getName() + ".findDuePlannedIdForUpdate");

        String sql = statement.getBoundSql(Map.of("workspaceId", 7))
            .getSql().replaceAll("\\s+", " ");

        assertTrue(sql.contains("w.enabled = TRUE"));
        assertTrue(sql.contains("w.runtime_owner = 'canonical'"));
        assertTrue(sql.contains("w.archived_at IS NULL"));
        assertTrue(sql.contains("w.intake_paused_at IS NULL"));
        assertTrue(sql.contains("w.active_version_id = e.workflow_version_id"));
        assertTrue(sql.contains("w.runtime_generation = e.workflow_runtime_generation"));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        try (InputStream input = WorkflowDateEnrollmentMapperXmlTest.class.getClassLoader()
                .getResourceAsStream("mappers/WorkflowDateEnrollmentMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                "mappers/WorkflowDateEnrollmentMapper.xml",
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
