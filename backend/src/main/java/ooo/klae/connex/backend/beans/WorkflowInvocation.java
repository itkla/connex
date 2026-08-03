package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Frozen exact-scope manual workflow invocation pinned to one immutable version. */
@Data
@NoArgsConstructor
public class WorkflowInvocation {
    private long id;
    private int workspaceId;
    private int workflowId;
    private long workflowVersionId;
    private Integer requestedById;
    private String scopeKind;
    private String resolvedScopeKind;
    private String sourceSurface;
    private String recordType;
    private byte[] scopeTokenHash;
    private byte[] scopeHash;
    private byte[] confirmationKey;
    private String scopeContractJson;
    private int exactCount;
    private int readyCount;
    private int skippedCount;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
