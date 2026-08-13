package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable metadata for one assistant tool proposal, decision, execution, and undo outcome. */
@Data
@NoArgsConstructor
public class AiChatToolCall {
    private int id;
    private int workspaceId;
    private int messageId;
    private int sessionId;
    private Integer requestedByUserId;
    private String toolName;
    private String status;
    private String argumentsJson;
    private String thoughtSignature;
    private String resultJson;
    private Integer executedByUserId;
    private String executedAt;
    private String idempotencyKey;
    private String createdAt;
    private String updatedAt;
}
