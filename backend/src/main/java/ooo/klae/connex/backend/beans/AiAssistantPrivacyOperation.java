package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable organization erasure authority with durable placement coverage. */
@Data
@NoArgsConstructor
public class AiAssistantPrivacyOperation {
    private int organizationId;
    private String operationKey;
    private String contentScope;
    private long cutoffGeneration;
    private long invalidatedGeneration;
    private String reason;
    private byte[] operationFingerprint;
    private Long dsrRequestId;
    private Integer controlAcceptanceAuditId;
    private LocalDateTime completedAt;
}
