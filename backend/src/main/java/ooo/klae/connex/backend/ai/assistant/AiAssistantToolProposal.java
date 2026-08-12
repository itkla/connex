package ooo.klae.connex.backend.ai.assistant;

/** Durable proposal identity and replay state returned by the persistence boundary. */
public record AiAssistantToolProposal(
        int id,
        String status,
        String resultJson,
        boolean created) {
}
