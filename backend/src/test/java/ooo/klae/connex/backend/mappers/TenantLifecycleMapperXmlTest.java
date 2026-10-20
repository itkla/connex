package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

/** Pins the registry-validated streaming shape used by tenant snapshots. */
class TenantLifecycleMapperXmlTest {

    @Test
    void proposalSetExportsCheckPrivacyButResidualCountsRemainUnconditional() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/TenantLifecycleMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = Map.of("workspaceId", 7,
                "declaration", TenantLifecycleRegistry.require("ai_assistant_proposal_set"));
        String namespace = TenantLifecycleMapper.class.getName() + ".";
        String export = configuration.getMappedStatement(namespace + "streamRows")
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
        assertTrue(export.contains("s.workspace_id = t0.workspace_id AND s.id = t0.session_id"));
        assertTrue(export.contains("t.id = t0.origin_turn_id AND m.id = t0.message_id"));
        assertTrue(export.contains("b.blocked = FALSE"));
        assertTrue(export.contains("b.generation = t0.privacy_generation"));
        assertTrue(export.contains("b.generation = COALESCE(t.privacy_generation, s.mission_taint_generation)"));
        assertTrue(export.contains("m.seq < s.mission_taint_seq"));
        assertTrue(export.contains("b.generation = COALESCE(m.privacy_generation, s.mission_taint_generation)"));
        assertTrue(export.contains("r.id = t.mission_run_id AND r.withheld_at IS NULL"));
        assertTrue(export.contains("r.id = m.mission_run_id AND r.withheld_at IS NULL"));
        String residual = configuration.getMappedStatement(namespace + "countRows")
                .getBoundSql(parameters).getSql();
        assertFalse(residual.contains("privacy"));
        assertFalse(residual.contains("withheld"));
        assertTrue(residual.contains("ai_assistant_proposal_set"));
    }

    @Test
    void objectReferencesUseOneStreamingDistinctUnionWithTheUsageLedger() throws Exception {
        String resource = "mappers/TenantLifecycleMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("id=\"streamRows\""));
        assertTrue(xml.contains("id=\"streamActiveObjectReferences\""));
        assertTrue(xml.contains("resultSetType=\"FORWARD_ONLY\""));
        assertTrue(xml.contains("fetchSize=\"-2147483648\""));
        assertTrue(xml.contains("useCache=\"false\""));
        assertTrue(xml.contains("UNION DISTINCT"));
        assertTrue(xml.contains("LEFT JOIN managed_object_usage"));
        assertTrue(xml.contains("ORDER BY candidates.object_key"));
        assertFalse(xml.contains("id=\"findActiveObjectReference\""));
        assertFalse(xml.contains("id=\"findActiveObjectReferencesAfter\""));
    }
}
