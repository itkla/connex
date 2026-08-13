package ooo.klae.connex.backend.ai.assistant;

import java.time.Duration;

final class AiAssistantTurnBudget {
    static final Duration TURN = Duration.ofSeconds(70);
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(5);
    static final Duration DURABLE_LIFETIME = TURN.plus(EXPIRY_GRACE);

    private AiAssistantTurnBudget() {
    }
}
