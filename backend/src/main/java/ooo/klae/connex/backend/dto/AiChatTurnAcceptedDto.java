package ooo.klae.connex.backend.dto;

/** Accepted assistant turn and its opaque shared-generation polling handle. */
public record AiChatTurnAcceptedDto(
        int turnId,
        int sessionId,
        String generationHandle,
        String status) {
}
