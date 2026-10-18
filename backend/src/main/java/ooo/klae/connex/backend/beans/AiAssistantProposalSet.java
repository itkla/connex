package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stored chat-call identity and immutable membership metadata, dormant until producer activation.
 * Publication is technical settlement, independent of the children's decision statuses.
 * Requester nullability is reserved for account erasure; it never transfers decision ownership.
 */
@Data
@NoArgsConstructor
public class AiAssistantProposalSet {
    private int workspaceId;
    private String proposalKey;
    private int sessionId;
    private int originTurnId;
    private int messageId;
    private Integer requestedByUserId;
    private int stepNumber;
    private int callOrdinal;
    private String toolName;
    private String invocationMode;
    private Integer bulkTurnSlot;
    private int rowCount;
    private String intentFingerprint;
    private String scopeRevision;
    private String publicationState;
    private String failureReason;
    private long privacyGeneration;
    private String createdAt;
}
