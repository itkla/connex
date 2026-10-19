package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable workspace privacy generation; absence is never an unblocked state. */
@Data
@NoArgsConstructor
public class AiAgentPrivacyBarrier {
    private int workspaceId;
    private long generation;
    private boolean blocked;
    private String latestOperationKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
