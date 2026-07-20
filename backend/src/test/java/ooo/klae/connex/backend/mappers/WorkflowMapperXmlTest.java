package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;

/** Verifies workflow mapper parsing, parameter bindings, scoping, locking, and version immutability. */
class WorkflowMapperXmlTest {

    @Test
    void workflowStatementsAreWorkspaceScopedAndLockTheMutableRow() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> workspace = Map.of("workspaceId", 7);
        Map<String, Object> identity = Map.of("workspaceId", 7, "id", 11);
        Map<String, Object> legacy = Map.of("workspaceId", 7, "legacyRuleId", 13);

        assertScoped(configuration, WorkflowMapper.class, "listByWorkspace", workspace);
        assertScoped(configuration, WorkflowMapper.class, "getById", identity);
        assertScoped(configuration, WorkflowMapper.class, "getByIdForUpdate", identity);
        assertScoped(configuration, WorkflowMapper.class, "getByLegacyRuleId", legacy);
        assertScoped(configuration, WorkflowMapper.class, "listUnpairedLegacyRules", workspace);

        Workflow workflow = workflow();
        assertScoped(configuration, WorkflowMapper.class, "insert", workflow);
        assertScoped(configuration, WorkflowMapper.class, "updateDraft", workflow);

        Map<String, Object> lifecycle = new HashMap<>(identity);
        lifecycle.put("enabled", true);
        lifecycle.put("updatedById", 17);
        assertScoped(configuration, WorkflowMapper.class, "updateLifecycle", lifecycle);

        Map<String, Object> active = new HashMap<>(identity);
        active.put("activeVersionId", 19L);
        active.put("updatedById", 17);
        assertScoped(configuration, WorkflowMapper.class, "updateActiveVersion", active);

        String lockSql = sql(configuration, WorkflowMapper.class, "getByIdForUpdate", identity);
        assertTrue(lockSql.endsWith("FOR UPDATE"));
        String legacySql = sql(configuration, WorkflowMapper.class, "listUnpairedLegacyRules", workspace);
        assertTrue(legacySql.contains("r.workspace_id = ?"));
        assertTrue(legacySql.contains("w.workspace_id = ?"));
        assertTrue(legacySql.endsWith("ORDER BY r.id"));
    }

    @Test
    void workflowVersionsAreWorkspaceScopedAndAppendOnly() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> identity = Map.of("workspaceId", 7, "workflowId", 11, "id", 19L);
        Map<String, Object> workflow = Map.of("workspaceId", 7, "workflowId", 11);
        assertScoped(configuration, WorkflowVersionMapper.class, "getById", identity);
        assertScoped(configuration, WorkflowVersionMapper.class, "listByWorkflow", workflow);
        assertScoped(configuration, WorkflowVersionMapper.class, "getLatest", workflow);
        assertScoped(configuration, WorkflowVersionMapper.class, "insert", version());

        String latestSql = sql(configuration, WorkflowVersionMapper.class, "getLatest", workflow);
        assertTrue(latestSql.contains("ORDER BY version_number DESC, id DESC"));
        assertTrue(latestSql.endsWith("LIMIT 1"));

        String xml = resource("mappers/WorkflowVersionMapper.xml");
        assertFalse(xml.contains("<update"));
        assertFalse(xml.contains("<delete"));
        assertEquals(1, occurrences(xml, "<insert"));
        assertEquals(3, occurrences(xml, "<select"));
    }

    @Test
    void mapperSqlUsesOnlyBoundParametersAndTenantPlaneTables() throws Exception {
        for (String resource : List.of(
                "mappers/WorkflowMapper.xml", "mappers/WorkflowVersionMapper.xml")) {
            String xml = resource(resource);
            assertFalse(xml.contains("${"));
            assertFalse(xml.contains("app_user"));
            assertFalse(xml.contains("workspace_member"));
            assertFalse(xml.contains("JOIN workspace"));
        }
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/WorkflowMapper.xml", "mappers/WorkflowVersionMapper.xml")) {
            try (InputStream input = WorkflowMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    private static void assertScoped(
            Configuration configuration, Class<?> mapper, String statement, Object parameters) {
        String sql = sql(configuration, mapper, statement, parameters);
        assertTrue(sql.contains("workspace_id"), statement);
        assertTrue(sql.contains("?"), statement);
        MappedStatement mapped = configuration.getMappedStatement(mapper.getName() + "." + statement);
        assertTrue(mapped.getBoundSql(parameters).getParameterMappings().stream()
            .anyMatch(mapping -> mapping.getProperty().endsWith("workspaceId")), statement);
    }

    private static String sql(
            Configuration configuration, Class<?> mapper, String statement, Object parameters) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setLegacyRuleId(13);
        workflow.setName("Workflow");
        workflow.setEnabled(false);
        workflow.setDraftRevision(1);
        workflow.setDraftRecordType("deal");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftRunAsUserId(17);
        workflow.setDraftDefinitionJson("{\"schemaVersion\":1}");
        workflow.setDraftCanvasJson("{}");
        workflow.setUpdatedById(17);
        return workflow;
    }

    private static WorkflowVersion version() {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(7);
        version.setWorkflowId(11);
        version.setVersionNumber(1);
        version.setName("Workflow");
        version.setRecordType("deal");
        version.setTriggerType("entity_change");
        version.setTriggerConfig("{}");
        version.setActionsJson("[]");
        version.setExecutionMode("user");
        version.setRunAsUserId(17);
        version.setDefinitionJson("{\"schemaVersion\":1}");
        version.setCanvasJson("{}");
        version.setDefinitionHash(new byte[32]);
        return version;
    }

    private static String resource(String resource) throws Exception {
        try (InputStream input = WorkflowMapperXmlTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String value, String token) {
        return value.split(Pattern.quote(token), -1).length - 1;
    }
}
