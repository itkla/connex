package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler;
import ooo.klae.connex.backend.ai.assistant.AiAssistantStepSchema;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.Toolset;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.exceptions.AiAgentBudgetInfeasibleException;
import tools.jackson.databind.ObjectMapper;

class AiAgentProposalBudgetServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler assembler = new AiAssistantPromptAssembler(json, catalog);
    private final AiAssistantStepSchema schema = new AiAssistantStepSchema(json, catalog);
    private final AiInvocationService invocations = new AiInvocationService(
            null, null, null, null, null, null, null, null, json, null, Clock.systemUTC());
    private final AiAgentProposalBudgetService budget = new AiAgentProposalBudgetService(assembler, schema, invocations);

    @Test
    void runtimeThreeMeasuresActualReachableProtocolEnvelopes() {
        var maxima = budget.maxima();
        assertTrue(maxima.working() > 0);
        assertTrue(maxima.closing() > 0);
        assertEquals(maxima, budget.maxima());
        int goal = json.writeValueAsBytes("Prepare useful changes").length;
        long exact = (long) maxima.working() + goal + 4096 + 256 + 65536;
        budget.requireFeasibleCreation(goal, Math.max(81920, exact));
        if (exact > 81920) {
            assertThrows(AiAgentBudgetInfeasibleException.class,
                    () -> budget.requireFeasibleCreation(goal, exact - 1));
        }
        assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> budget.requireFeasibleCreation(48003, 131072));
    }

    @Test
    void nativeClosingIncludesItsActualDefinitions() {
        var policy = new AiAgentExecutionPolicy(1, 1, 1, null, null,
                new AiAgentScopePolicy("deal", 1), 4, Instant.MAX, null, null, null, null,
                3, 1, 1, null, true, 65536);
        policy.execute(() -> {
            var loaded = EnumSet.of(Toolset.CORE, Toolset.WRITE_PIPELINE, Toolset.WRITE_FIELDS);
            var definitions = assembler.nativeToolDefinitions(loaded);
            int full = invocations.serializedPromptBytes(assembler.fixedNativePrompt(loaded), schema.finalResponseSchema(),
                    AiReasoningMode.TAGGED, new AiNativeToolRequest(definitions, List.of(), null, true));
            int omitted = invocations.serializedPromptBytes(assembler.fixedNativePrompt(loaded), schema.finalResponseSchema(),
                    AiReasoningMode.TAGGED, new AiNativeToolRequest(List.of(definitions.getFirst()), List.of(), null, true));
            assertTrue(full > omitted);
            return null;
        });
        assertTrue(budget.maxima().closing() > 0);
    }

    @Test
    void runtimeOneAndTwoAdmissionRemainsUnchanged() {
        AiAgentBudgetService.requireFeasibleCreation(48002, 129639);
        assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> AiAgentBudgetService.requireFeasibleCreation(48002, 129638));
        assertEquals(11749, AiAgentBudgetService.FIXED_ENVELOPE_BYTES);
        assertEquals(8, AiAgentMissionCatalog.maxSegments(3));
    }
}
