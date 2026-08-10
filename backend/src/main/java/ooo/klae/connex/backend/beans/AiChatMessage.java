package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One durably ordered message within an assistant chat session. */
@Data
@NoArgsConstructor
public class AiChatMessage {
    private int id;
    private int workspaceId;
    private int sessionId;
    private int seq;
    private String authorKind;
    private Integer authorUserId;
    private String content;
    private String createdAt;
}
