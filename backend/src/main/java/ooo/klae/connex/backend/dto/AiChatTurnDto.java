package ooo.klae.connex.backend.dto;

import java.util.Objects;

import ooo.klae.connex.backend.beans.AiChatTurn;

/** Caller-safe durable status for one assistant turn. */
public record AiChatTurnDto(
        int turnId,
        int sessionId,
        String status,
        String terminalReason) {

    /** Creates the caller-safe durable turn projection. */
    public static AiChatTurnDto from(AiChatTurn turn) {
        Objects.requireNonNull(turn, "turn");
        return new AiChatTurnDto(
                turn.getId(), turn.getSessionId(), turn.getStatus(), turn.getTerminalReason());
    }
}
