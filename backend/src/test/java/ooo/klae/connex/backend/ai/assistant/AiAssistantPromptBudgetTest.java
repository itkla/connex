package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;

class AiAssistantPromptBudgetTest {

    @Test
    void safeDefaultContextProducesIndependentConservativeBudgets() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        32_768),
                16_384,
                8_192);

        assertEquals(8_192, budget.maxOutputTokens());
        assertTrue(budget.historyBytes() > 0);
        assertTrue(budget.pageContextBytes() > 0);
        assertTrue(budget.toolResultBytes() > 0);
        assertEquals(
                budget.compactionSourceBytes(),
                budget.historyBytes() + budget.pageContextBytes() + budget.toolResultBytes());
        assertTrue(budget.compactionSourceBytes() * 12 + 8_192
                <= 32_768 - budget.maxOutputTokens());
    }

    @Test
    void undersizedProviderIsRejectedBeforePromptAssembly() {
        assertThrows(
                ooo.klae.connex.backend.ai.provider.AiProviderException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                4_096),
                        1_024));
    }
}
