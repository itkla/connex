package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Organization-wide authority; historical tenant generations never acquire this identity. */
@Data
@NoArgsConstructor
public class AiAssistantPrivacyFence {
    private int organizationId;
    private long generation;
    private String bootstrapOperationId;
    private LocalDateTime bootstrapCompletedAt;
}
