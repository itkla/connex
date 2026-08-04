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

        assertScoped(
            configuration,
            WorkflowMapper.class,
            "listByWorkspace",
            Map.of("workspaceId", 7, "archived", false));
        assertScoped(
            configuration,
            WorkflowMapper.class,
            "listItemsByWorkspace",
            Map.of("workspaceId", 7, "archived", false));
        assertScoped(configuration, WorkflowMapper.class, "getById", identity);
        assertScoped(configuration, WorkflowMapper.class, "getByIdForUpdate", identity);
        assertScoped(configuration, WorkflowMapper.class, "getByLegacyRuleId", legacy);
        assertScoped(configuration, WorkflowMapper.class, "getByLegacyRuleIdForUpdate", legacy);
        assertScoped(configuration, WorkflowMapper.class, "listUnpairedLegacyRules", workspace);
        assertScoped(configuration, WorkflowMapper.class, "countLegacyRuleLinks", workspace);
        assertScoped(configuration, WorkflowMapper.class, "countUnpairedLegacyRules", workspace);
        assertScoped(configuration, WorkflowMapper.class, "firstUnpairedLegacyRuleId", workspace);

        Workflow workflow = workflow();
        assertScoped(configuration, WorkflowMapper.class, "insert", workflow);
        Map<String, Object> draft = new HashMap<>();
        draft.put("workflow", workflow);
        draft.put("expectedRevision", 1);
        assertScoped(configuration, WorkflowMapper.class, "updateDraft", draft);

        Map<String, Object> firstPublication = new HashMap<>(identity);
        firstPublication.put("legacyRuleId", 13);
        firstPublication.put("activeVersionId", 19L);
        firstPublication.put("updatedById", 17);
        firstPublication.put("expectedRevision", 1);
        assertScoped(
            configuration, WorkflowMapper.class, "assignFirstPublication", firstPublication);
        Map<String, Object> firstCanonicalPublication = new HashMap<>(identity);
        firstCanonicalPublication.put("activeVersionId", 19L);
        firstCanonicalPublication.put("updatedById", 17);
        firstCanonicalPublication.put("expectedRevision", 1);
        assertScoped(
            configuration,
            WorkflowMapper.class,
            "assignFirstCanonicalPublication",
            firstCanonicalPublication);

        Map<String, Object> laterPublication = new HashMap<>(identity);
        laterPublication.put("expectedLegacyRuleId", 13);
        laterPublication.put("expectedActiveVersionId", 19L);
        laterPublication.put("activeVersionId", 23L);
        laterPublication.put("updatedById", 17);
        laterPublication.put("expectedRevision", 1);
        assertScoped(
            configuration, WorkflowMapper.class, "advancePublication", laterPublication);
        Map<String, Object> laterCanonicalPublication = new HashMap<>(identity);
        laterCanonicalPublication.put("expectedActiveVersionId", 19L);
        laterCanonicalPublication.put("activeVersionId", 23L);
        laterCanonicalPublication.put("updatedById", 17);
        laterCanonicalPublication.put("expectedRevision", 1);
        assertScoped(
            configuration,
            WorkflowMapper.class,
            "advanceCanonicalPublication",
            laterCanonicalPublication);

        Map<String, Object> lifecycle = new HashMap<>(identity);
        lifecycle.put("enabled", true);
        lifecycle.put("updatedById", 17);
        assertScoped(configuration, WorkflowMapper.class, "updateLifecycle", lifecycle);

        Map<String, Object> legacyReplacement = new HashMap<>();
        legacyReplacement.put("workflow", workflow);
        legacyReplacement.put("activeVersionId", 23L);
        legacyReplacement.put("expectedLegacyRuleId", 13);
        legacyReplacement.put("expectedActiveVersionId", 19L);
        legacyReplacement.put("expectedRevision", 1);
        assertScoped(
            configuration,
            WorkflowMapper.class,
            "replaceLegacyPublication",
            legacyReplacement);

        Map<String, Object> archive = new HashMap<>(identity);
        archive.put("updatedById", 17);
        assertScoped(configuration, WorkflowMapper.class, "archive", archive);
        assertScoped(configuration, WorkflowMapper.class, "restore", archive);

        Map<String, Object> owner = new HashMap<>(identity);
        owner.put("expectedActiveVersionId", 19L);
        owner.put("expectedOwner", "legacy");
        owner.put("runtimeOwner", "canonical");
        owner.put("updatedById", 17);
        assertScoped(
            configuration, WorkflowMapper.class, "compareAndSwapRuntimeOwner", owner);
        owner.put("legacyRuleId", 13);
        assertScoped(
            configuration,
            WorkflowMapper.class,
            "attachLegacyRuleAndCompareAndSwapRuntimeOwner",
            owner);

        Map<String, Object> active = new HashMap<>(identity);
        active.put("activeVersionId", 19L);
        active.put("updatedById", 17);
        assertScoped(configuration, WorkflowMapper.class, "updateActiveVersion", active);

        String lockSql = sql(configuration, WorkflowMapper.class, "getByIdForUpdate", identity);
        assertTrue(lockSql.endsWith("FOR UPDATE"));
        String legacyLockSql = sql(
            configuration, WorkflowMapper.class, "getByLegacyRuleIdForUpdate", legacy);
        assertTrue(legacyLockSql.endsWith("FOR UPDATE"));
        String draftSql = sql(configuration, WorkflowMapper.class, "updateDraft", draft);
        assertTrue(draftSql.contains("draft_revision = draft_revision + 1"));
        assertTrue(draftSql.contains("draft_revision = ?"));
        assertFalse(draftSql.contains("active_version_id"));
        assertFalse(draftSql.contains("legacy_rule_id"));
        assertFalse(draftSql.contains("enabled ="));
        String firstPublicationSql = sql(
            configuration, WorkflowMapper.class, "assignFirstPublication", firstPublication);
        assertTrue(firstPublicationSql.contains("SET legacy_rule_id = ?, active_version_id = ?"));
        assertTrue(firstPublicationSql.contains("draft_revision = ?"));
        assertTrue(firstPublicationSql.contains("legacy_rule_id IS NULL"));
        assertTrue(firstPublicationSql.contains("active_version_id IS NULL"));
        assertFalse(firstPublicationSql.contains("draft_revision = draft_revision + 1"));
        String laterPublicationSql = sql(
            configuration, WorkflowMapper.class, "advancePublication", laterPublication);
        assertTrue(laterPublicationSql.contains("draft_revision = ?"));
        assertTrue(laterPublicationSql.contains("legacy_rule_id = ?"));
        assertTrue(laterPublicationSql.contains("active_version_id = ?"));
        assertFalse(laterPublicationSql.contains("draft_revision = draft_revision + 1"));
        String legacySql = sql(configuration, WorkflowMapper.class, "listUnpairedLegacyRules", workspace);
        assertTrue(legacySql.contains("r.workspace_id = ?"));
        assertTrue(legacySql.contains("w.workspace_id = ?"));
        assertTrue(legacySql.endsWith("ORDER BY r.id"));
        String listItems = sql(
            configuration,
            WorkflowMapper.class,
            "listItemsByWorkspace",
            Map.of("workspaceId", 7, "archived", false));
        assertTrue(listItems.contains("LEFT JOIN LATERAL"));
        assertTrue(listItems.contains("wr.workspace_id = w.workspace_id"));
        assertTrue(listItems.contains("re.workspace_id = w.workspace_id"));
        assertTrue(listItems.contains("ORDER BY wr.started_at DESC, wr.id DESC LIMIT 1"));
        assertTrue(listItems.contains("ORDER BY re.executed_at DESC, re.id DESC LIMIT 1"));
        assertFalse(listItems.contains("draft_canvas_json"));

        String replacementSql = sql(
            configuration,
            WorkflowMapper.class,
            "replaceLegacyPublication",
            legacyReplacement);
        assertTrue(replacementSql.contains("draft_revision = draft_revision + 1"));
        assertTrue(replacementSql.contains("legacy_rule_id = ?"));
        assertTrue(replacementSql.contains("active_version_id = ?"));
        assertTrue(replacementSql.contains("draft_revision = ?"));
        String mapperXml = resource("mappers/WorkflowMapper.xml");
        assertFalse(mapperXml.contains("<delete"));
        assertTrue(mapperXml.contains("archived_at IS NULL"));
        assertTrue(mapperXml.contains("archived_at IS NOT NULL"));
    }

    @Test
    void legacyBackfillEnumerationAndLocksAreStableAndContentScoped() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> workspace = Map.of("workspaceId", 7);

        String enumeration = sql(configuration, RuleMapper.class, "workspaceIdsWithRules", Map.of());
        assertEquals("SELECT DISTINCT workspace_id FROM rule ORDER BY workspace_id", enumeration);
        assertFalse(enumeration.contains("?"));

        assertScoped(configuration, RuleMapper.class, "getByWorkspaceForUpdate", workspace);
        String locked = sql(configuration, RuleMapper.class, "getByWorkspaceForUpdate", workspace);
        assertTrue(locked.contains("WHERE workspace_id = ?"));
        assertTrue(locked.endsWith("ORDER BY id FOR UPDATE"));

        assertScoped(configuration, RuleMapper.class, "countByWorkspace", workspace);
        assertEquals(
            "SELECT COUNT(*) FROM rule WHERE workspace_id = ?",
            sql(configuration, RuleMapper.class, "countByWorkspace", workspace));

        Map<String, Object> identity = Map.of("workspaceId", 7, "id", 23);
        assertScoped(configuration, RuleMapper.class, "getByIdForUpdate", identity);
        assertTrue(sql(configuration, RuleMapper.class, "getByIdForUpdate", identity)
            .endsWith("FOR UPDATE"));

        Map<String, Object> lifecycle = Map.of("workspaceId", 7, "id", 23, "enabled", true);
        assertScoped(configuration, RuleMapper.class, "updateEnabled", lifecycle);
        assertTrue(sql(configuration, RuleMapper.class, "updateEnabled", lifecycle)
            .contains("enabled <> ?"));

        assertScoped(
            configuration, RuleMapper.class, "getLatestExecutionsByWorkspace", workspace);
        String latestExecutions = sql(
            configuration, RuleMapper.class, "getLatestExecutionsByWorkspace", workspace);
        assertTrue(latestExecutions.contains("JOIN LATERAL"));
        assertTrue(latestExecutions.contains(
            "re.workspace_id = r.workspace_id AND re.rule_id = r.id"));
        assertTrue(latestExecutions.contains(
            "ORDER BY re.executed_at DESC, re.id DESC LIMIT 1"));
        assertTrue(latestExecutions.endsWith("WHERE r.workspace_id = ? ORDER BY r.id"));

        Map<String, Object> dispatch = Map.of(
            "workspaceId", 7, "triggerType", "entity_change");
        String enabledDispatch = sql(
            configuration, RuleMapper.class, "getEnabledByTrigger", dispatch);
        assertTrue(enabledDispatch.contains("LEFT JOIN workflow w"));
        assertTrue(enabledDispatch.contains("w.id IS NULL"));
        assertTrue(enabledDispatch.contains("w.runtime_owner = 'legacy'"));
        assertTrue(enabledDispatch.contains("w.archived_at IS NULL"));
        assertFalse(enabledDispatch.contains("w.enabled = TRUE"));

        String scheduledWorkspaces = sql(
            configuration,
            RuleMapper.class,
            "workspaceIdsWithEnabledScheduleRules",
            Map.of());
        assertTrue(scheduledWorkspaces.contains("LEFT JOIN workflow w"));
        assertTrue(scheduledWorkspaces.contains("w.id IS NULL"));
        assertFalse(scheduledWorkspaces.contains("w.enabled = TRUE AND w.runtime_owner = 'legacy'"));

        String latestIndex = resource(
            "db/migration/tenant/V121__rule_execution_latest_index.sql")
            .replaceAll("\\s+", " ")
            .trim();
        assertTrue(latestIndex.contains("idx_rule_execution_workspace_latest"));
        assertTrue(latestIndex.contains(
            "(workspace_id, rule_id, executed_at DESC, id DESC)"));

        String linked = sql(configuration, WorkflowMapper.class, "countLegacyRuleLinks", workspace);
        assertTrue(linked.contains("WHERE workspace_id = ?"));
        assertTrue(linked.contains("legacy_rule_id IS NOT NULL"));

        String unpaired = sql(configuration, WorkflowMapper.class, "countUnpairedLegacyRules", workspace);
        assertTrue(unpaired.contains("r.workspace_id = ?"));
        assertTrue(unpaired.contains("w.workspace_id = ?"));

        String firstUnpaired = sql(
            configuration, WorkflowMapper.class, "firstUnpairedLegacyRuleId", workspace);
        assertTrue(firstUnpaired.contains("r.workspace_id = ?"));
        assertTrue(firstUnpaired.contains("w.workspace_id = ?"));
        assertTrue(firstUnpaired.endsWith("ORDER BY r.id LIMIT 1"));
    }

    @Test
    void workflowVersionsAreWorkspaceScopedAndAppendOnly() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> identity = Map.of("workspaceId", 7, "workflowId", 11, "id", 19L);
        Map<String, Object> workflow = Map.of("workspaceId", 7, "workflowId", 11);
        assertScoped(configuration, WorkflowVersionMapper.class, "getById", identity);
        assertScoped(configuration, WorkflowVersionMapper.class, "getByIdForUpdate", identity);
        assertScoped(configuration, WorkflowVersionMapper.class, "listByWorkflow", workflow);
        assertScoped(configuration, WorkflowVersionMapper.class, "getLatest", workflow);
        assertScoped(configuration, WorkflowVersionMapper.class, "insert", version());

        String latestSql = sql(configuration, WorkflowVersionMapper.class, "getLatest", workflow);
        assertTrue(latestSql.contains("ORDER BY version_number DESC, id DESC"));
        assertTrue(latestSql.endsWith("LIMIT 1"));
        String lockedVersionSql = sql(
            configuration, WorkflowVersionMapper.class, "getByIdForUpdate", identity);
        assertTrue(lockedVersionSql.endsWith("FOR UPDATE"));
        String xml = resource("mappers/WorkflowVersionMapper.xml");
        assertFalse(xml.contains("<delete"));
        assertEquals(1, occurrences(xml, "<insert"));
        assertEquals(5, occurrences(xml, "<select"));
        assertFalse(xml.contains("<update"));
    }

    @Test
    void mapperSqlUsesOnlyBoundParametersAndTenantPlaneTables() throws Exception {
        for (String resource : List.of(
                "mappers/RuleMapper.xml",
                "mappers/WorkflowMapper.xml",
                "mappers/WorkflowVersionMapper.xml")) {
            String xml = resource(resource);
            assertFalse(xml.contains("${"));
            assertFalse(xml.contains("app_user"));
            assertFalse(xml.contains("workspace_member"));
            assertFalse(xml.contains("JOIN workspace"));
        }
    }

    @Test
    void offboardingDiscoveryIsStrictlyUserBoundAndExactWritesStayWorkspaceScoped() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> user = Map.of("userId", 23);
        for (Map.Entry<Class<?>, String> statement : Map.<Class<?>, String>of(
                WorkflowMapper.class, "findAffectedByUserAnywhere",
                WorkflowVersionMapper.class, "findLockCandidatesByUserAnywhere",
                RuleMapper.class, "findLockCandidatesByUserAnywhere").entrySet()) {
            String discovery = sql(
                configuration, statement.getKey(), statement.getValue(), user);
            assertTrue(discovery.contains("created_by_id = ?"));
            assertTrue(discovery.contains("run_as_user_id = ?"));
            assertFalse(discovery.contains("${"));
        }
        String workflowDiscovery = sql(
            configuration, WorkflowMapper.class, "findAffectedByUserAnywhere", user);
        String versionDiscovery = sql(
            configuration, WorkflowVersionMapper.class, "findLockCandidatesByUserAnywhere", user);
        String ruleDiscovery = sql(
            configuration, RuleMapper.class, "findLockCandidatesByUserAnywhere", user);
        assertTrue(workflowDiscovery.contains("v.id = w.active_version_id"));
        assertTrue(versionDiscovery.contains("w.active_version_id = v.id"));
        assertTrue(versionDiscovery.startsWith("SELECT v.id, v.workspace_id, v.workflow_id"));
        assertTrue(ruleDiscovery.contains("v.id = w.active_version_id"));

        Map<String, Object> workflow = Map.of("workspaceId", 7, "id", 11, "userId", 23);
        assertScoped(configuration, WorkflowMapper.class, "redactUserReferences", workflow);
        assertScoped(configuration, WorkflowMapper.class, "disableForOffboarding", workflow);
        Map<String, Object> rule = Map.of("workspaceId", 7, "id", 13, "userId", 23);
        assertScoped(configuration, RuleMapper.class, "redactUserReferences", rule);
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String resource : List.of(
                "mappers/RuleMapper.xml",
                "mappers/WorkflowMapper.xml",
                "mappers/WorkflowVersionMapper.xml")) {
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
