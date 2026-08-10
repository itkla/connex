package ooo.klae.connex.backend.ai;

/** Receives the winning terminal transition for one asynchronous AI generation. */
@FunctionalInterface
public interface AiGenerationTerminalListener {
    /** Listener used by generation callers that do not own a second durable state record. */
    AiGenerationTerminalListener NO_OP = (outcome, reason) -> {};

    /**
     * Receives the terminal transition after it wins the generation registry race.
     * @param outcome terminal outcome
     * @param reason stable failure or timeout reason, or {@code null} when resolved
     */
    void onTerminal(AiGenerationTaskResult.Outcome outcome, String reason);
}
