package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable semantic action identity and immutable preparation provenance; decisions belong to the canonical tool call. */
@Data
@NoArgsConstructor
public class AiAgentAction {
    private int id;
    private int workspaceId;
    private int rootRunId;
    private int producerRunId;
    private String actionSlot;
    private String semanticSlotKey;
    private String targetKind;
    private int targetId;
    private String toolName;
    private String planItemKey;
    private long planRevision;
    private String preparationId;
    private byte[] intentFingerprint;
    private LocalDateTime preparedAt;
    private LocalDateTime sourceObservedAt;
    private String sourceVersionsJson;
    private Integer originSegmentId;
    private Long originExecutionGeneration;
    private Integer outputOrdinal;
    private long privacyGeneration;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
