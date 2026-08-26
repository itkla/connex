package ooo.klae.connex.backend.ai.assistant;

import java.time.Duration;

/**
 * Wall-clock bounds for one assistant turn.
 *
 * <p>With the step ceiling raised, the deadline is the bound that actually ends a long research
 * turn, so it is sized for a full step budget of provider calls rather than a handful: a turn may
 * take the time it needs, and the deadline exists to end a hung provider or a pathological loop,
 * not ordinary thoroughness.
 */
final class AiAssistantTurnBudget {
    static final Duration TURN = Duration.ofSeconds(180);
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(5);
    static final Duration DURABLE_LIFETIME = TURN.plus(EXPIRY_GRACE);

    private AiAssistantTurnBudget() {
    }
}
