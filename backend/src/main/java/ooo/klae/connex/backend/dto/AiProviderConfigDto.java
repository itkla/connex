package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import ooo.klae.connex.backend.beans.AiProviderConfig;

/**
 * Per-organization AI provider settings returned to the client. The credential
 * secret and secret-store reference are never included.
 */
@Data
public class AiProviderConfigDto {
    private String provider;
    private String region;
    private String endpoint;
    private String apiVersion;
    private String deployment;
    private String projectId;
    private boolean allowInternalEndpoint;
    private String modelId;
    private boolean hasCredential;
    private String credentialLast4;
    private boolean noTrainingAttested;
    private boolean enabled;
    private LocalDateTime updatedAt;

    /**
     * Maps stored AI provider settings to the client view.
     * @param config the stored config, or null when none exists
     * @return the DTO, disabled and empty when config is null
     */
    public static AiProviderConfigDto from(AiProviderConfig config) {
        AiProviderConfigDto dto = new AiProviderConfigDto();
        if (config == null) {
            return dto;
        }
        dto.setProvider(config.getProvider());
        dto.setRegion(config.getRegion());
        dto.setEndpoint(config.getEndpoint());
        dto.setApiVersion(config.getApiVersion());
        dto.setDeployment(config.getDeployment());
        dto.setProjectId(config.getProjectId());
        dto.setAllowInternalEndpoint(config.isAllowInternalEndpoint());
        dto.setModelId(config.getModelId());
        dto.setHasCredential(config.getCredentialRef() != null && !config.getCredentialRef().isBlank());
        dto.setCredentialLast4(config.getCredentialLast4());
        dto.setNoTrainingAttested(config.isNoTrainingAttested());
        dto.setEnabled(config.isEnabled());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }
}
