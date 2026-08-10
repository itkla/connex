package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatMessage;

/** API representation of one assistant chat message. */
@Data
@NoArgsConstructor
public class AiChatMessageDto {
    private int id;
    private int sessionId;
    private int seq;
    private String authorKind;
    private Integer authorUserId;
    private String content;
    private String createdAt;
    private List<AiChatCitationDto> citations = List.of();

    /** Maps a persisted message to its API representation. */
    public static AiChatMessageDto from(AiChatMessage message) {
        return from(message, List.of());
    }

    /** Maps a persisted message with citations authorized for the current viewer. */
    public static AiChatMessageDto from(
            AiChatMessage message, List<AiChatCitationDto> citations) {
        AiChatMessageDto dto = new AiChatMessageDto();
        dto.setId(message.getId());
        dto.setSessionId(message.getSessionId());
        dto.setSeq(message.getSeq());
        dto.setAuthorKind(message.getAuthorKind());
        dto.setAuthorUserId(message.getAuthorUserId());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());
        dto.setCitations(List.copyOf(citations));
        return dto;
    }
}
