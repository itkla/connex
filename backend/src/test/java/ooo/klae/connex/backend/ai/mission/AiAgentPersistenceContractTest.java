package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

class AiAgentPersistenceContractTest {
    @Test
    void v231ConstraintsAndLifecycleMatchContract() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/tenant/V231__ai_agent_runs.sql"));
        assertTrue(migration.contains("message_seq_high_water INT NOT NULL DEFAULT 0"));
        assertTrue(migration.contains("m.workspace_id = s.workspace_id AND m.session_id = s.id"));
        assertTrue(migration.contains("SET s.message_seq_high_water = COALESCE(m.maximum_seq, 0)"));
        assertTrue(migration.contains("FOREIGN KEY (workspace_id, id, active_segment_id)"));
        assertTrue(migration.contains("FOREIGN KEY (workspace_id, mission_run_id, mission_segment_id)"));
        assertTrue(migration.contains("UNIQUE KEY uq_ai_agent_privacy_work_generation (workspace_id, invalidated_generation)"));
        assertTrue(migration.contains("ON DELETE RESTRICT ON UPDATE RESTRICT"));
        assertFalse(migration.contains("REFERENCES workspace"));
        assertFalse(migration.contains("REFERENCES app_user"));
        for (String table : Set.of("ai_agent_run", "ai_agent_segment", "ai_agent_privacy_barrier", "ai_agent_privacy_work")) {
            assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains(table));
            assertEquals(table, TenantLifecycleRegistry.require(table).table());
        }
        assertTrue(TenantLifecycleRegistry.require("ai_chat_turn").deleteOrder()
                < TenantLifecycleRegistry.require("ai_agent_segment").deleteOrder());
        assertTrue(TenantLifecycleRegistry.require("ai_agent_segment").deleteOrder()
                < TenantLifecycleRegistry.require("ai_agent_run").deleteOrder());
        assertTrue(TenantLifecycleRegistry.require("ai_agent_privacy_work").deleteOrder()
                < TenantLifecycleRegistry.require("ai_agent_privacy_barrier").deleteOrder());
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains("ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper"));
        assertTrue(TenantScopeInterceptor.CONTROL_PLANE_NAMESPACES.contains("ooo.klae.connex.backend.mappers.AiAgentDsrControlMapper"));
    }

    @Test
    void purgeAndResidualShareTheImmutableMessageBoundary() throws Exception {
        String mapper = Files.readString(Path.of("src/main/resources/mappers/AiAgentPrivacyMapper.xml"));
        String residual = mapper.substring(mapper.indexOf("<sql id=\"sessionResidual\">"));
        assertTrue(mapper.contains("m.seq &gt;= s.mission_taint_seq"));
        assertTrue(mapper.contains("COALESCE(m.privacy_generation, s.mission_taint_generation) &lt;= #{cutoffGeneration}"));
        assertTrue(residual.contains("<include refid=\"affectedMessage\" />"));
        assertFalse(residual.contains("#{maxId}"));
        assertFalse(residual.contains("#{afterId}"));
        assertFalse(mapper.contains("SET message_seq_high_water"));
        assertTrue(mapper.contains("AND revision = #{expectedRevision}"));
    }
}
