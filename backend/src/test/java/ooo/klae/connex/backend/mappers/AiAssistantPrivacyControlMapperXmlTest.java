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
