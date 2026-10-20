package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** Pins lifecycle cleanup authority to the exact workspace root or its durable tombstone. */
class AiAssistantPrivacyControlMapperXmlTest {
    @Test
    void notificationAuthoritySqlHasOnlyRecipientCandidatesRegardlessOfUnrelatedTenants() throws Exception {
        Configuration configuration = configuration();
        var statement = configuration.getMappedStatement(
                AiAssistantPrivacyControlMapper.class.getName() + ".notificationWorkspaceGenerations");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("recipientId", 11);
        parameters.put("workspaceId", null);
        var bound = statement.getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("wm.workspace_id = w.id AND wm.user_id = ? AND wm.status = 'active'"));
        assertEquals(List.of("recipientId"), bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        parameters.put("workspaceId", 7);
        assertEquals(2, statement.getBoundSql(parameters).getParameterMappings().size());
        parameters.put("recipientId", null);
        assertTrue(statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").contains("WHERE w.id = ?"));
        parameters.put("workspaceId", null);
        assertTrue(statement.getBoundSql(parameters).getSql().contains("FALSE"));
    }

    @Test
    void deletedWorkspaceEvidenceRequiresExactVerifiedReceiptAndAbsentLiveRoot() throws Exception {
        var bound = configuration().getMappedStatement(
                AiAssistantPrivacyControlMapper.class.getName() + ".deletedWorkspaceVerifiedAt")
                .getBoundSql(Map.of("organizationId", 3, "operationKey", "operation", "workspaceId", 7));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("receipt.organization_id = ? AND receipt.operation_key = ? AND receipt.workspace_id = ?"));
        assertTrue(sql.contains("receipt.state = 'verified'"));
        assertTrue(sql.contains("receipt.verified_work_revision IS NOT NULL AND receipt.verified_at IS NOT NULL"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM workspace w WHERE w.id = receipt.workspace_id)"));
    }

    @Test
    void organizationRecoveryDiscoveryDoesNotDependOnWorkspaceRows() throws Exception {
        var bound = configuration().getMappedStatement(
                AiAssistantPrivacyControlMapper.class.getName() + ".incompleteOrganizationIdsPage")
                .getBoundSql(Map.of("afterId", 0, "limit", 100));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("FROM ai_assistant_privacy_operation o"));
        assertTrue(sql.contains("o.completed_at IS NULL OR EXISTS"));
        assertFalse(sql.contains("FROM workspace "));
        assertTrue(sql.endsWith("ORDER BY o.organization_id LIMIT ?"));
    }

    private Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/AiAssistantPrivacyControlMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    @Test
    void lifecycleAuthorityUsesFreshExactRootsBeforeCleanupTombstones() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/AiAssistantPrivacyControlMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(
                AiAssistantPrivacyControlMapper.class.getName() + ".workspaceLifecycleState");
        var bound = statement.getBoundSql(Map.of("workspaceId", 7));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains("SELECT w.lifecycle_state, 0 AS targetOrder FROM workspace w WHERE w.id = ?"));
        assertTrue(sql.contains("UNION ALL SELECT 'tearing_down' AS lifecycle_state, 1 AS targetOrder"
                + " FROM tenant_cleanup_tombstone t WHERE t.workspace_id = ?"));
        assertTrue(sql.endsWith("ORDER BY targetOrder LIMIT 1"));
        assertEquals(List.of("workspaceId", "workspaceId"),
                bound.getParameterMappings().stream().map(parameter -> parameter.getProperty()).toList());
        assertFalse(statement.isUseCache());
        assertTrue(statement.isFlushCacheRequired());
    }
}
