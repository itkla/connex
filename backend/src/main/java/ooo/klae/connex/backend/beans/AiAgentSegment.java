package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One attempt of a mission segment, including reserved and observed allowance. */
@Data
@NoArgsConstructor
public class AiAgentSegment {
    private int id;
    private int workspaceId;
    private int runId;
    private int segmentNo;
    private int attemptNo;
    private int reservedSteps;
    private int knownSteps;
    private Integer turnId;
    private long executionGeneration;
    private long privacyGeneration;
    private long reservedTokens;
    private long knownInputTokens;
    private long knownOutputTokens;
    private boolean usageUncertain;
    private String status;
    private String failureReason;
    private byte[] providerDeclarationFingerprint;
    private LocalDateTime deadlineAt;
    private LocalDateTime startedAt;
    private LocalDateTime settledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
