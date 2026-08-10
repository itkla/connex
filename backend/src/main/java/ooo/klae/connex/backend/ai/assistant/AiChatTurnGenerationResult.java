package ooo.klae.connex.backend.ai.assistant;

/** Minimal non-transcript result retained behind the shared generation handle. */
public record AiChatTurnGenerationResult(int turnId, String status) {
}
