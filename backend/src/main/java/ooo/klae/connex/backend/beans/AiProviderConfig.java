package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.ToString;

/**
 * Per-organization BYOP AI provider settings. Credential material lives in the
 * central secret store; this bean carries only the secret reference and masked
 * metadata needed by the settings UI and readiness gate.
 */
@Data
@ToString(exclude = "credentialRef")
public class AiProviderConfig {
    private int orgId;
    private String provider;
    private String region;
    private String modelId;
    private String credentialRef;
    private String credentialLast4;
    private boolean noTrainingAttested;
    private LocalDateTime attestedAt;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
