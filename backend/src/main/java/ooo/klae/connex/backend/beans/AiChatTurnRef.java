package ooo.klae.connex.backend.beans;

/**
 * The session and turn keys of one assistant turn, with none of its content.
 *
 * <p>Background maintenance passes enumerate turns to act on and then re-read each one under its
 * row lock, so they need the keys and nothing else. Projecting the full turn instead would carry
 * the model's durable partial answer and the turn's scope JSON for every visited workspace onto a
 * shared scheduler thread, and would make any later log line or exception message on that path a
 * disclosure rather than a cost.
 *
 * @param sessionId the owning session
 * @param id the turn
 */
public record AiChatTurnRef(int sessionId, int id) {
}
