package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable workspace-scoped assistant conversation metadata. */
@Data
@NoArgsConstructor
public class AiChatSession {
    private int id;
    private int workspaceId;
    private Integer createdByUserId;
    private String title;
    private boolean titleUserSet = true;
    private String visibility;
    private String status;
    private String participationStatus;
    private boolean ownedByCurrentUser;
    private boolean historySummarized;
    private String lastMessageAt;
    private String archivedAt;
    private String createdAt;
    private String updatedAt;
}
