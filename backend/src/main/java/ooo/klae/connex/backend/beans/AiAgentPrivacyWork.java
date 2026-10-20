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
    private ContentScope contentScope = ContentScope.MISSION_V1;
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
    /** Serializes the closed scope domain used by the existing mapper and receipt protocols. */
    public String getContentScope() {
        return contentScope.value;
    }

    /** Rejects unknown scopes before they can select generation semantics. */
    public void setContentScope(String value) {
        contentScope = switch (java.util.Objects.requireNonNull(value, "contentScope")) {
            case "organization_mission_holdings_v1" -> ContentScope.MISSION_V1;
            case "organization_assistant_holdings_v2" -> ContentScope.ASSISTANT_V2;
            default -> throw new IllegalArgumentException("Unknown assistant privacy scope");
        };
    }

    private enum ContentScope {
        MISSION_V1("organization_mission_holdings_v1"),
        ASSISTANT_V2("organization_assistant_holdings_v2");

        private final String value;

        ContentScope(String value) {
            this.value = value;
        }
    }
}
