package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiStructuredOutputEnforcement;

class AiAssistantPromptBudgetTest {

    private static final int FLOOR = AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS;

    @Test
    void safeDefaultContextProducesIndependentConservativeBudgets() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        FLOOR,
                        8_192),
                16_384,
                8_192);

        assertEquals(8_192, budget.maxOutputTokens());
        assertTrue(budget.historyBytes() >= 4_096);
        assertTrue(budget.attachmentContextBytes() >= 256);
        assertTrue(budget.pageContextBytes() >= 256);
        assertTrue(budget.toolResultBytes() >= 2_048);
        assertEquals(8_192, budget.repairEnvelopeBytes());
        assertEquals(
                budget.compactionSourceBytes(),
                budget.historyBytes() + budget.attachmentContextBytes()
                        + budget.pageContextBytes() + budget.toolResultBytes());
        assertEquals(229_376, AiProviderCapabilities.estimatedInputByteCeiling(
                FLOOR, budget.maxOutputTokens()));
        assertEquals(57_344, AiProviderCapabilities.conservativeInputByteCeiling(
                FLOOR, budget.maxOutputTokens()));
        assertTrue(budget.compactionSourceBytes() * 12
                + budget.repairEnvelopeBytes() + 8_192
                <= AiProviderCapabilities.estimatedInputByteCeiling(
                        FLOOR, budget.maxOutputTokens()));
        assertTrue(budget.compactionSourceBytes()
                + budget.repairEnvelopeBytes() + 8_192
                <= AiProviderCapabilities.conservativeInputByteCeiling(
                        FLOOR, budget.maxOutputTokens()));
    }

    /**
     * Pins the floor itself and the honest refusal below it.
     *
     * <p>A 32k model is the case the floor exists for: it still admits the fixed envelope, so
     * nothing further down the pipeline would object, and the turn would run to a truncated answer.
     * The refusal has to happen here, where the context size first becomes known and before any
     * prompt is assembled or sent.
     */
    @Test
    void aContextWindowBelowTheAssistantFloorIsRefusedBeforePromptAssembly() {
        assertEquals(65_536, AiAssistantPromptBudget.ASSISTANT_MIN_CONTEXT_TOKENS);

        AiAssistantLoopException undersized = assertThrows(
                AiAssistantLoopException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                4_096,
                                1_024),
                        1_024));
        AiAssistantLoopException thirtyTwoK = assertThrows(
                AiAssistantLoopException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                32_768,
                                8_192),
                        16_384,
                        16_962));
        AiAssistantLoopException oneBelowTheFloor = assertThrows(
                AiAssistantLoopException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                FLOOR - 1,
                                8_192),
                        16_384,
                        16_962));

        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                undersized.terminalReason());
        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                thirtyTwoK.terminalReason());
        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                thirtyTwoK.detailReason());
        assertEquals(AiAssistantTerminalReasons.CONTEXT_WINDOW_TOO_SMALL,
                oneBelowTheFloor.terminalReason());
    }

    @Test
    void exactlyTheFloorStillFundsTheConfiguredOutputBudget() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        FLOOR,
                        8_192),
                16_384,
                16_962);

        assertEquals(8_192, budget.maxOutputTokens());
        assertTrue(budget.historyBytes() >= 4_096);
        assertTrue(budget.toolResultBytes() >= 2_048);
    }

    @Test
    void fixedEnvelopePressureReducesOutputBeforeItCanConsumeInputFloors() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        FLOOR,
                        8_192),
                8_192,
                50_000);

        assertTrue(budget.maxOutputTokens() < 8_192);
        assertFalse(budget.outputTokensClamped());
        assertTrue(budget.historyBytes() >= 4_096);
        assertTrue(budget.toolResultBytes() >= 2_048);
        assertTrue(budget.pageContextBytes() >= 256);
        assertTrue(budget.attachmentContextBytes() >= 256);
        assertTrue(budget.compactionSourceBytes()
                + budget.repairEnvelopeBytes() + 50_000
                <= AiProviderCapabilities.conservativeInputByteCeiling(
                        FLOOR, budget.maxOutputTokens()));
    }

    @Test
    void fixedAndRepairEnvelopesThatCannotLeaveInputFloorsAreRejected() {
        assertThrows(
                ooo.klae.connex.backend.ai.provider.AiProviderException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                FLOOR,
                                8_192),
                        8_192,
                        51_000));
        assertThrows(
                ooo.klae.connex.backend.ai.provider.AiProviderException.class,
                () -> AiAssistantPromptBudget.from(
                        new AiProviderCapabilities(
                                AiStructuredOutputEnforcement.PROMPT_ONLY,
                                AiReasoningMode.TAGGED,
                                FLOOR,
                                8_192),
                        8_192,
                        Integer.MAX_VALUE));
    }

    @Test
    void providerOutputCapacityClampsAConfiguredAssistantBudget() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        200_000,
                        4_096),
                8_192,
                8_192);

        assertEquals(4_096, budget.maxOutputTokens());
        assertTrue(budget.outputTokensClamped());
    }

    @Test
    void toolFloorAndUtf8TruncationStayInsideTheBudgetBoundary() {
        AiAssistantPromptBudget budget = new AiAssistantPromptBudget(
                64, 4_096, 256, 256, 2_048, 8_000);

        String truncated = budget.truncateUtf8("A😀B", 4);

        assertEquals(2_048, budget.minimumToolResultBytes());
        assertEquals("A", truncated);
        assertTrue(budget.fits(truncated, 4));
        assertEquals(1, budget.utf8Bytes(truncated));
    }
}
