package ooo.klae.connex.backend.dto;

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

    /** Maps a persisted message to its API representation. */
    public static AiChatMessageDto from(AiChatMessage message) {
        AiChatMessageDto dto = new AiChatMessageDto();
        dto.setId(message.getId());
        dto.setSessionId(message.getSessionId());
        dto.setSeq(message.getSeq());
        dto.setAuthorKind(message.getAuthorKind());
        dto.setAuthorUserId(message.getAuthorUserId());
        dto.setContent(message.getContent());
        dto.setCreatedAt(message.getCreatedAt());
        return dto;
    }
}
