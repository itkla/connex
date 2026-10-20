package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.mission.AiAgentExecutionPolicy;
import ooo.klae.connex.backend.ai.mission.AiAgentScopePolicy;
import ooo.klae.connex.backend.beans.AiChatMessage;
import tools.jackson.databind.ObjectMapper;

class AiAgentToolSchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();

    @Test
    void missionBookkeepingIsHiddenFromReactiveAndLegacyToolDefinitions() {
        assertFalse(catalog.isKnown("checkpoint_segment"));
        assertTrue(catalog.nativeDefinitions(json, AiAssistantToolCatalog.CORE).stream()
                .noneMatch(tool -> "checkpoint_segment".equals(tool.name())));
        policy(1).execute(() -> {
            assertTrue(catalog.nativeDefinitions(json, AiAssistantToolCatalog.CORE).stream()
                    .noneMatch(tool -> AiAgentToolSchema.bookkeeping(tool.name())));
            return null;
        });
    }

    @Test
    void runtimeTwoUsesStrictBookkeepingInBothProviderProtocols() {
        policy(2).execute(() -> {
            String nativeSchema = json.writeValueAsString(catalog.nativeDefinitions(json,
                    AiAssistantToolCatalog.CORE));
            String stepSchema = json.writeValueAsString(new AiAssistantStepSchema(json, catalog)
                    .responseSchema(AiAssistantToolCatalog.CORE));
            for (String schema : List.of(nativeSchema, stepSchema)) {
                assertTrue(schema.contains("expectedCheckpointRevision"));
                assertTrue(schema.contains("checkpoint_segment"));
                assertTrue(schema.contains("additionalProperties"));
                assertFalse(schema.contains("memberProvenance"));
                assertFalse(schema.contains("evidenceReferences"));
            }
            var args = json.readTree("""
                    {"expectedPlanRevision":"1","expectedCheckpointRevision":"1","items":[
                    {"key":null,"label":"Read record","status":"active","completion":null}]}
                    """);
            assertTrue(catalog.permitsArguments("set_todos", args));
            ((tools.jackson.databind.node.ObjectNode) args).put("coverage", 1);
            assertFalse(catalog.permitsArguments("set_todos", args));
            return null;
        });
    }

    @Test
    void continuationClosingSchemaExposesOnlyCheckpointAndExistingFinalAnswer() {
        String schema = json.writeValueAsString(new AiAssistantStepSchema(json, catalog)
                .missionClosingResponseSchema());
        assertTrue(schema.contains("checkpoint_segment"));
        assertTrue(schema.contains("citations"));
        assertFalse(schema.contains("get_record"));
        assertFalse(schema.contains("set_todos"));
        assertFalse(schema.contains("coverage"));
    }

    @Test
    void newestBookkeepingResultCanFitAfterOrdinaryPriorEvidenceIsEvicted() {
        var assembler = new AiAssistantPromptAssembler(json, catalog);
        var prior = new AiAssistantPromptAssembler.ToolTurn(1, 0, "get_record",
                new AiAssistantToolResult(java.util.Map.of("notes", "Prior evidence ".repeat(1000)), List.of()));
        var latest = new AiAssistantPromptAssembler.ToolTurn(2, 0, "set_todos",
                new AiAssistantToolResult(java.util.Map.of("planRevision", "2", "checkpointRevision", "2",
                        "plan", java.util.Map.of("items", List.of(java.util.Map.of("key", "p0001",
                                "label", "Read", "status", "active")))), List.of()));
        var audit = assembler.requireAdditionalMissionBookkeepingCapacity(false, List.of(prior), latest,
                java.util.Map.of(), new ooo.klae.connex.backend.ai.masking.MaskingContext(),
                new AiAssistantPromptBudget(64, 4000, 256, 256, 600, 8000));
        assertTrue(audit.degraded());
        assertTrue(audit.evictedToolExchanges() > 0);
    }

    private static AiAgentExecutionPolicy policy(int runtime) {
        var turn = new AiChatQueuedTurn(7, 11, 13, 17, 19, 1, 1, false,
                List.of(), List.of(), AiPrivacyMode.MASKED, false);
        return new AiAgentExecutionPolicy(23, 29, 1, turn, new AiChatMessage(),
                new AiAgentScopePolicy("person", 31), 4, Instant.MAX, null, null, null, null,
                runtime, 1, 1, null, true, 65536);
    }
}
