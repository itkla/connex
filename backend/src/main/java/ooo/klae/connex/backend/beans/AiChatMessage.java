package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One durably ordered message within an assistant chat session. */
@Data
@NoArgsConstructor
public class AiChatMessage {
    private int id;
    private Integer missionRunId;
    private String missionMessageKind;
    private Long privacyGeneration;
    private int workspaceId;
    private long organizationPrivacyGeneration;
    private int sessionId;
    private int seq;
    private String authorKind;
    private Integer authorUserId;
    private String content;
    private String structuredJson;
    private Integer inputTokens;
    private Integer outputTokens;
    private String createdAt;
}
