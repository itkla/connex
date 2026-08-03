package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Verifies tenant scoping and exact failure classification in workflow operations SQL. */
class WorkflowOperationsMapperXmlTest {

    @Test
    void failureFilterExcludesHealthyRunsAndMatchesPersistedCategories() throws Exception {
        Configuration configuration = configuration();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("workspaceId", 7);
        parameters.put("status", null);
        parameters.put("failureCategory", "execution");
        parameters.put("ownerId", null);
        parameters.put("beforeStartedAt", null);
        parameters.put("beforeId", null);
        parameters.put("limit", 26);

        String sql = sql(configuration, "getOperationsRuns", parameters);

        assertTrue(sql.contains("wr.workspace_id = ?"));
        assertTrue(sql.contains("WHEN wr.failure_code IS NULL THEN NULL"));
        assertTrue(sql.contains("wr.failure_code = 'transient_database_failure'"));
        assertTrue(sql.contains("'definition_corrupt', 'definition_invalid') THEN 'configuration'"));
        assertFalse(sql.contains("${"));

        String invocationRecords = sql(configuration, "getInvocationRecords", Map.of(
            "workspaceId", 7,
            "invocationId", 31L));
        String refreshRecords = sql(configuration, "refreshInvocationRecords", Map.of(
            "workspaceId", 7,
            "invocationId", 31L));
        assertTrue(invocationRecords.contains("'configuration_invalid', 'invalid_action_config'"));
        assertTrue(refreshRecords.contains("'configuration_invalid', 'invalid_action_config'"));
    }

    @Test
    void globalUserReferenceCleanupChangesOnlyColumnsMatchingTheRequestedUser() throws Exception {
        String cleanup = sql(
            configuration(),
            "clearUserReferencesAnywhere",
            Map.of("userId", 41));

        assertTrue(cleanup.contains(
            "w.intake_paused_by_id = IF(w.intake_paused_by_id = ?, NULL, w.intake_paused_by_id)"));
        assertTrue(cleanup.contains(
            "origin.installed_by_id = IF( origin.installed_by_id = ?, NULL, origin.installed_by_id)"));
        assertTrue(cleanup.contains(
            "invocation.requested_by_id = IF( invocation.requested_by_id = ?, NULL, invocation.requested_by_id)"));
        assertTrue(cleanup.contains(
            "intervention.owner_user_id = IF( intervention.owner_user_id = ?, NULL, intervention.owner_user_id)"));
        assertTrue(cleanup.contains(
            "WHERE w.intake_paused_by_id = ? OR origin.installed_by_id = ?"
                + " OR invocation.requested_by_id = ? OR intervention.owner_user_id = ?"));
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases(
            "ooo.klae.connex.backend.beans");
        try (InputStream input = WorkflowOperationsMapperXmlTest.class
                .getClassLoader().getResourceAsStream("mappers/WorkflowOperationsMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                "mappers/WorkflowOperationsMapper.xml",
                configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(
            Configuration configuration,
            String statement,
            Object parameters) {
        return configuration.getMappedStatement(
            WorkflowOperationsMapper.class.getName() + "." + statement)
            .getBoundSql(parameters)
            .getSql()
            .replaceAll("\\s+", " ")
            .trim();
    }
}
