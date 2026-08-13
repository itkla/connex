package ooo.klae.connex.backend.dto;

import java.util.Objects;

import ooo.klae.connex.backend.beans.AiChatTurn;

/** Caller-safe durable status for one assistant turn. */
public record AiChatTurnDto(
        int turnId,
        int sessionId,
        String status,
        String terminalReason,
        String partialContent) {

    /** Creates a non-streaming turn projection. */
    public AiChatTurnDto(int turnId, int sessionId, String status, String terminalReason) {
        this(turnId, sessionId, status, terminalReason, null);
    }

    /** Creates the caller-safe durable turn projection. */
    public static AiChatTurnDto from(AiChatTurn turn) {
        Objects.requireNonNull(turn, "turn");
        return new AiChatTurnDto(
                turn.getId(), turn.getSessionId(), turn.getStatus(), turn.getTerminalReason(),
                turn.isStreamed() ? turn.getPartialContent() : null);
    }
}
