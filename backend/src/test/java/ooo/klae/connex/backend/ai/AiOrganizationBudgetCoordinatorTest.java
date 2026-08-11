package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.PromptAssembly;

class AiOrganizationBudgetCoordinatorTest {
    @Test
    void reservationCeilingUsesUtf8BytesForMultilingualPrompts() {
        String system = "簡潔に回答";
        String user = "関係性を要約";
        AiInvocation invocation = new AiInvocation(
                AiFeature.ASSISTANT_CHAT,
                new MaskingContext(),
                PromptAssembly.builder().system(system).userTurn(user).build(),
                64,
                0.1);

        assertEquals(
                64L
                        + system.getBytes(StandardCharsets.UTF_8).length
                        + user.getBytes(StandardCharsets.UTF_8).length,
                AiOrganizationBudgetCoordinator.estimatedTokenCeiling(invocation));
    }
}
