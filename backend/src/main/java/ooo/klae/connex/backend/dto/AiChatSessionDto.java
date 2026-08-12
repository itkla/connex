package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatSession;

/** API representation of an accessible assistant chat session. */
@Data
@NoArgsConstructor
public class AiChatSessionDto {
    private int id;
    private int workspaceId;
    private Integer createdByUserId;
    private String title;
    private String visibility;
    private String status;
    private String participationStatus;
    private boolean archived;
    private boolean ownedByCurrentUser;
    private String lastMessageAt;
    private String archivedAt;
    private String createdAt;
    private String updatedAt;

    /** Maps a persisted session to its caller-relative API representation. */
    public static AiChatSessionDto from(AiChatSession session) {
        AiChatSessionDto dto = new AiChatSessionDto();
        dto.setId(session.getId());
        dto.setWorkspaceId(session.getWorkspaceId());
        dto.setCreatedByUserId(session.getCreatedByUserId());
        dto.setTitle(session.getTitle());
        dto.setVisibility(session.getVisibility());
        dto.setStatus(session.getStatus());
        dto.setParticipationStatus(session.getParticipationStatus());
        dto.setArchived("archived".equals(session.getStatus()));
        dto.setOwnedByCurrentUser(session.isOwnedByCurrentUser());
        dto.setLastMessageAt(session.getLastMessageAt());
        dto.setArchivedAt(session.getArchivedAt());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        return dto;
    }
}
