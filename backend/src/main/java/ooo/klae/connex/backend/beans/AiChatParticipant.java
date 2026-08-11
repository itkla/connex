package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Tenant-scoped invitation or joined participant row for an assistant session. */
@Data
@NoArgsConstructor
public class AiChatParticipant {
    private int workspaceId;
    private int sessionId;
    private int userId;
    private Integer invitedByUserId;
    private String role;
    private String status;
    private String invitedAt;
    private String joinedAt;
}
