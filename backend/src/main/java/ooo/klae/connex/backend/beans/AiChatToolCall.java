package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable metadata for one assistant tool proposal, decision, execution, and undo outcome. */
@Data
@NoArgsConstructor
public class AiChatToolCall {
    private int id;
    private Integer missionRunId;
    private String proposalOrigin = "legacy";
    private Integer missionActionId;
    private String artifactOrigin = "reactive";
    private Long privacyGeneration;
    private int workspaceId;
    private long organizationPrivacyGeneration;
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
    private boolean serverProposal;
    private String proposalKey;
    private Integer proposalOrdinal;
    private String proposalPreparedAt;
    private String invocationMode;
    private String publicationState;
    private String createdAt;
    private String updatedAt;
}
