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

    /**
     * The allocation arithmetic was written when every adapter reported 200,000 tokens or fewer.
     * A million-token window multiplies the byte ceiling by five, and the derivation multiplies
     * that ceiling again by the masked-serialization expansion factor, so this pins that the
     * intermediate products stay inside {@code int} and that every allocation is still positive
     * and still sums to the compaction source budget.
     */
    @Test
    void aMillionTokenWindowProducesPositiveNonOverflowingAllocations() {
        AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        1_000_000,
                        128_000),
                16_384,
                16_962);

        assertEquals(16_384, budget.maxOutputTokens());
        assertFalse(budget.outputTokensClamped());
        assertTrue(budget.historyBytes() > 0);
        assertTrue(budget.attachmentContextBytes() > 0);
        assertTrue(budget.pageContextBytes() > 0);
        assertTrue(budget.toolResultBytes() > 0);
        assertTrue(budget.compactionSourceBytes() > 0);
        assertEquals(
                budget.compactionSourceBytes(),
                budget.historyBytes() + budget.attachmentContextBytes()
                        + budget.pageContextBytes() + budget.toolResultBytes());
        assertTrue(budget.historyBytes() > 100_000,
                "a million-token model must fund far more history than a 64k model");
        assertTrue(budget.compactionSourceBytes() * 12
                + budget.repairEnvelopeBytes() + 16_962
                <= AiProviderCapabilities.estimatedInputByteCeiling(
                        1_000_000, budget.maxOutputTokens()));
        assertTrue(budget.compactionSourceBytes()
                + budget.repairEnvelopeBytes() + 16_962
                <= AiProviderCapabilities.conservativeInputByteCeiling(
                        1_000_000, budget.maxOutputTokens()));
    }

    /**
     * The percentage arithmetic in {@link AiChatMemoryService} multiplies {@code historyBytes} by
     * 80 and by 60 before dividing, so the derived history allocation has to leave headroom for
     * those products. This asserts the headroom directly rather than trusting that a million is
     * "obviously small enough".
     */
    @Test
    void derivedHistoryAllocationsLeaveHeadroomForPercentageArithmetic() {
        for (int contextTokens : new int[] {65_536, 200_000, 1_000_000, 1_050_000, 2_097_152}) {
            AiAssistantPromptBudget budget = AiAssistantPromptBudget.from(
                    new AiProviderCapabilities(
                            AiStructuredOutputEnforcement.PROMPT_ONLY,
                            AiReasoningMode.TAGGED,
                            contextTokens,
                            8_192),
                    16_384,
                    16_962);

            assertTrue(budget.historyBytes() > 0, "history at " + contextTokens);
            assertTrue(budget.historyBytes() <= Integer.MAX_VALUE / 80,
                    "history*80 must not overflow at " + contextTokens);
            assertTrue(budget.compactionSourceBytes() <= Integer.MAX_VALUE / 12,
                    "compaction*12 must not overflow at " + contextTokens);
            assertTrue(budget.historyBytes() * 80 / 100 > 0, "at " + contextTokens);
            assertTrue(budget.historyBytes() * 60 / 100 > 0, "at " + contextTokens);
        }
    }

    /**
     * A million-token model still has a 128,000-token output ceiling, so an operator who raises
     * {@code connex.ai.assistant-max-output-tokens} above it must be clamped to the provider's
     * number and told the clamp happened.
     */
    @Test
    void theProviderOutputCeilingStillClampsAtAMillionTokenWindow() {
        AiAssistantPromptBudget clamped = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        1_000_000,
                        128_000),
                200_000,
                16_962);
        AiAssistantPromptBudget partnerCeiling = AiAssistantPromptBudget.from(
                new AiProviderCapabilities(
                        AiStructuredOutputEnforcement.PROMPT_ONLY,
                        AiReasoningMode.TAGGED,
                        1_000_000,
                        64_000),
                200_000,
                16_962);

        assertEquals(128_000, clamped.maxOutputTokens());
        assertTrue(clamped.outputTokensClamped());
        assertTrue(clamped.toolResultBytes() > 0);
        assertEquals(64_000, partnerCeiling.maxOutputTokens());
        assertTrue(partnerCeiling.outputTokensClamped());
        assertTrue(partnerCeiling.compactionSourceBytes() > clamped.compactionSourceBytes(),
                "reserving fewer output tokens must leave more input budget");
    }

    /**
     * A million-token window is nowhere near the point at which the shared byte estimate
     * saturates, so the estimate must remain exact rather than pinned at {@link Integer#MAX_VALUE}.
     */
    @Test
    void byteCeilingsRemainExactRatherThanSaturatingAtAMillionTokens() {
        assertEquals(3_488_000,
                AiProviderCapabilities.estimatedInputByteCeiling(1_000_000, 128_000));
        assertEquals(872_000,
                AiProviderCapabilities.conservativeInputByteCeiling(1_000_000, 128_000));
        assertEquals(8_355_840,
                AiProviderCapabilities.estimatedInputByteCeiling(2_097_152, 8_192));
        assertEquals(2_088_960,
                AiProviderCapabilities.conservativeInputByteCeiling(2_097_152, 8_192));
        assertEquals(Integer.MAX_VALUE,
                AiProviderCapabilities.estimatedInputByteCeiling(Integer.MAX_VALUE, 128_000));
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
