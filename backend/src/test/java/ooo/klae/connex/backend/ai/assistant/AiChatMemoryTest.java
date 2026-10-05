package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;

/** The turn's recorded call bound can only ever be one the declaration ceiling admits. */
class AiChatMemoryTest {

    private static final AiAssistantPromptBudget BUDGET =
            new AiAssistantPromptBudget(64, 64_000, 16_000, 16_000, 16_000, 112_000);

    @Test
    void refusesACallBoundOutsideTheDeclarationCeiling() {
        int max = AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS;
        for (int outOfRange : List.of(0, -1, max + 1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AiChatMemory(List.of(), BUDGET, 0, 0, true, outOfRange),
                    "bound " + outOfRange);
        }
        assertDoesNotThrow(() -> new AiChatMemory(List.of(), BUDGET, 0, 0, true, max));
    }

    @Test
    void theConvenienceConstructorsRecordOneCallPerStep() {
        assertEquals(1, new AiChatMemory(List.of(), BUDGET, 0, 0).parallelToolCalls());
        assertEquals(1, new AiChatMemory(List.of(), BUDGET, 0, 0, true).parallelToolCalls());
    }
}
