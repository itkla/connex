package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Tenant-local durable state and immutable request identity for one bounded mission. */
@Data
@NoArgsConstructor
public class AiAgentRun {
    private int id;
    private int workspaceId;
    private int definitionVersion;
    private int runtimeContractVersion;
    private int maxSteps;
    private int reservedSteps;
    private int consumedSteps;
    private Integer sessionId;
    private Integer ownerUserId;
    private Integer activeSegmentId;
    private Integer reportMessageId;
    private String clientRequestKey;
    private String missionType;
    private String locale;
    private String goal;
    private String scopeJson;
    private String status;
    private String reasonCode;
    private byte[] requestHash;
    private byte[] scopeFingerprint;
    private long stateVersion;
    private long executionGeneration;
    private long maxTokens;
    private long reservedTokens;
    private long consumedTokens;
    private long privacyGeneration;
    private Integer planSchemaVersion;
    private long planRevision;
    private String planJson;
    private Integer checkpointSchemaVersion;
    private long checkpointRevision;
    private String checkpointJson;
    private Integer memorySchemaVersion;
    private String memoryJson;
    private int lastCommittedSegmentNo;
    private long proposalMembershipRevision;
    private LocalDateTime proposalFrozenAt;
    private LocalDateTime proposalFirstPreparedAt;
    private LocalDateTime reviewExpiresAt;
    private LocalDateTime executionDeadlineAt;
    private LocalDateTime hardDeadlineAt;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime withheldAt;
    private LocalDateTime purgedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
