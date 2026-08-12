package ooo.klae.connex.backend.dto;

import java.util.List;

/** Current bounded presence and typing projection for an accessible assistant session. */
public record AiChatPresenceDto(
        int sessionId,
        List<AiChatParticipantDto> present,
        List<Integer> typingUserIds) {

    public AiChatPresenceDto {
        present = List.copyOf(present);
        typingUserIds = List.copyOf(typingUserIds);
    }
}
