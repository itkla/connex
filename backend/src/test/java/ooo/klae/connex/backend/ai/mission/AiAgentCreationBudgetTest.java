package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPromptAssembler;
import ooo.klae.connex.backend.ai.assistant.AiAssistantStepSchema;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog;
import ooo.klae.connex.backend.ai.provider.AiNativeToolRequest;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.exceptions.AiAgentBudgetInfeasibleException;
import tools.jackson.databind.ObjectMapper;

/** Creation accounting uses the same complete UTF-8 JSON envelopes as dispatch admission. */
class AiAgentCreationBudgetTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiAssistantToolCatalog catalog = new AiAssistantToolCatalog();
    private final AiAssistantPromptAssembler assembler = new AiAssistantPromptAssembler(json, catalog);
    private final AiAssistantStepSchema schema = new AiAssistantStepSchema(json, catalog);
    private final AiInvocationService invocations = new AiInvocationService(
            null, null, null, null, null, null, null, null, json, null, Clock.systemUTC());

    @Test
    void envelopeAndClosingReserveArePinnedToDispatchSerializationOnBothProtocols() {
        var policy = new AiAgentExecutionPolicy(31, 37, 1, null, null,
                new AiAgentScopePolicy("person", 41), 4, Instant.parse("2030-01-01T00:00:00Z"),
                null, null, null, null, 2, 1, 1, null, true, 16384);
        policy.execute(() -> {
            var nativeTools = new AiNativeToolRequest(
                    assembler.nativeToolDefinitions(AiAssistantToolCatalog.ALL), List.of());
            var closingTools = new AiNativeToolRequest(nativeTools.definitions(), List.of(), null, true);
            assertEquals(8616, invocations.serializedPromptBytes(
                    assembler.fixedNativePrompt(AiAssistantToolCatalog.ALL), schema.finalResponseSchema(),
                    AiReasoningMode.NONE, nativeTools));
            assertEquals(8856, invocations.serializedPromptBytes(
                    assembler.fixedNativePrompt(AiAssistantToolCatalog.ALL), schema.finalResponseSchema(),
                    AiReasoningMode.TAGGED, closingTools));
            int fallback = invocations.serializedPromptBytes(assembler.fixedPrompt(AiAssistantToolCatalog.ALL),
                    schema.responseSchema(AiAssistantToolCatalog.ALL), AiReasoningMode.TAGGED);
            assertEquals(11749, fallback);
            assertEquals(fallback, AiAgentBudgetService.FIXED_ENVELOPE_BYTES);
            int closing = invocations.serializedPromptBytes(assembler.fixedPrompt(AiAssistantToolCatalog.ALL),
                    schema.closingResponseSchema(), AiReasoningMode.TAGGED);
            assertEquals(8152, closing);
            assertTrue(closing <= fallback);
            long largestReport = AiAgentBudgetService.MAX_SERIALIZED_GOAL_BYTES + (long) fallback
                    + AiAgentBudgetService.CONTEXT_RESERVE_BYTES + AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS;
            assertEquals(64103, largestReport);
            assertTrue(largestReport <= AiAgentBudgetService.CLOSING_RESERVE_TOKENS);
            return null;
        });
    }

    @Test
    void everyAcceptedAllowanceFundsACheckpointAndTheClosingReport() {
        int goal = json.writeValueAsBytes("Review this record").length;
        for (long allowance = AiAgentBudgetService.MIN_RUN_TOKENS; allowance <= 131072; allowance++) {
            AiAgentBudgetService.requireFeasibleCreation(goal, allowance);
        }
        assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> AiAgentBudgetService.requireFeasibleCreation(goal, AiAgentBudgetService.MIN_RUN_TOKENS - 1));
        long exact = AiAgentBudgetService.MAX_SERIALIZED_GOAL_BYTES + (long) AiAgentBudgetService.FIXED_ENVELOPE_BYTES
                + AiAgentBudgetService.CONTEXT_RESERVE_BYTES + AiAgentBudgetService.MIN_DISPATCH_OUTPUT_TOKENS
                + AiAgentBudgetService.CLOSING_RESERVE_TOKENS;
        assertEquals(129639, exact);
        assertDoesNotThrow(() -> AiAgentBudgetService.requireFeasibleCreation(48002, exact));
        assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> AiAgentBudgetService.requireFeasibleCreation(48002, exact - 1));
    }

    @Test
    void defaultFundsMaximumJapaneseGoalAndEscapedGoalsHaveAnExplicitByteLimit() {
        long defaultTokens = new AiAgentRunCreateRequest.Limits(null, null, null).maxTokens();
        assertEquals(131072, defaultTokens);
        int japaneseBytes = json.writeValueAsBytes("日".repeat(16000)).length;
        assertEquals(48002, japaneseBytes);
        assertDoesNotThrow(() -> AiAgentBudgetService.requireFeasibleCreation(japaneseBytes, defaultTokens));
        var refused = assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> AiAgentBudgetService.requireFeasibleCreation(japaneseBytes, AiAgentBudgetService.MIN_RUN_TOKENS));
        assertEquals("mission_budget_infeasible", refused.getCode());
        int escaped = json.writeValueAsBytes("\u0001".repeat(16000)).length;
        assertEquals(96002, escaped);
        assertThrows(AiAgentBudgetInfeasibleException.class,
                () -> AiAgentBudgetService.requireFeasibleCreation(escaped, defaultTokens));
    }
}
