package ooo.klae.connex.backend.ai.assistant;

/** Durable terminal projection published after a generation callback settles. */
public record AiChatDurableTerminal(String status, String reason, int offset) {
    public AiChatDurableTerminal {
        if (status == null || status.isBlank() || offset < 0) {
            throw new IllegalArgumentException("Assistant durable terminal state is invalid");
        }
    }
}
