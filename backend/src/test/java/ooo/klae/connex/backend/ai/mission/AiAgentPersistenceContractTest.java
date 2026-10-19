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
    void v232ConstraintsAndLifecycleMatchContract() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/tenant/V232__ai_agent_runs.sql"));
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
    void v234ContinuationWritersAndResidualEnrollmentStayLosslessAndScoped() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/tenant/V234__ai_agent_continuation.sql"));
        assertTrue(migration.contains("ALGORITHM=INSTANT, LOCK=DEFAULT"));
        assertTrue(migration.contains("ALGORITHM=INPLACE, LOCK=NONE"));
        assertFalse(migration.toLowerCase(java.util.Locale.ROOT).contains("from the"));
        assertFalse(migration.contains("ADD CONSTRAINT"));
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains("ooo.klae.connex.backend.mappers.AiAgentRunMapper"));
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var document = factory.newDocumentBuilder().parse(Path.of("src/main/resources/mappers/AiAgentRunMapper.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        for (String statement : Set.of("insertRun", "updateRun", "updateContinuation")) {
            String sql = xpath.evaluate("/mapper/*[@id='" + statement + "']", document);
            for (String field : Set.of("planJson", "checkpointJson", "memoryJson")) {
                assertTrue(sql.contains("CONVERT(#{run." + field
                        + ",jdbcType=VARBINARY,typeHandler=ooo.klae.connex.backend.mappers.AiIdentifierUtf8TextTypeHandler} USING utf8mb4)"), statement);
            }
        }
        String privacy = Files.readString(Path.of("src/main/resources/mappers/AiAgentPrivacyMapper.xml"));
        assertEquals(2, privacy.split("<include refid=\"runHasResidualPayload\" />", -1).length - 1);
        for (String column : Set.of("plan_schema_version", "plan_revision", "plan_json", "checkpoint_schema_version",
                "checkpoint_revision", "checkpoint_json", "memory_schema_version", "memory_json", "last_committed_segment_no")) {
            assertTrue(privacy.contains("r." + column), column);
        }
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
