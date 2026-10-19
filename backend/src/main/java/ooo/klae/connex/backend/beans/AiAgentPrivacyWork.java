package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Metadata-only invalidation identity, frozen discovery bounds, and atomic cleanup cursor. */
@Data
@NoArgsConstructor
public class AiAgentPrivacyWork {
    private long id;
    private long cutoffGeneration;
    private long invalidatedGeneration;
    private long revision;
    private int workspaceId;
    private int maxRunId;
    private int maxSessionId;
    private int maxTurnId;
    private int maxMessageId;
    private int maxToolCallId;
    private int lastRunId;
    private int lastSessionId;
    private int lastTurnId;
    private int lastToolCallId;
    private int lastMessageId;
    private int retryCount;
    private Long dsrRequestId;
    private Long lastResidualCount;
    private Integer controlAcceptanceAuditId;
    private Integer currentSessionId;
    private String operationKey;
    private String reason;
    private String phase;
    private String sessionPhase;
    private String lastErrorCode;
    private byte[] operationFingerprint;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
