package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * Upsert of an organization's BYOP AI provider settings. Conditional validation,
 * credential bundling, and encryption live in the service; a null or blank
 * provider credential preserves any stored credential for the same provider.
 */
@Data
@ToString(exclude = {
        "accessKeyId", "secretAccessKey", "sessionToken", "apiKey", "serviceAccountJson"
})
public class AiProviderConfigRequest {
    @NotBlank
    @Size(max = 32)
    private String provider;

    @Size(max = 64)
    private String region;

    @NotBlank
    @Size(max = 128)
    private String modelId;

    @Size(max = 512)
    private String endpoint;

    @Size(max = 32)
    private String apiVersion;

    @Size(max = 128)
    private String deployment;

    @Size(max = 128)
    private String projectId;

    private boolean allowInternalEndpoint;

    @Size(max = 128)
    private String accessKeyId;

    @Size(max = 512)
    private String secretAccessKey;

    @Size(max = 4096)
    private String sessionToken;

    @Size(max = 512)
    private String apiKey;

    @Size(max = 8192)
    private String serviceAccountJson;

    private boolean noTrainingAttested;

    private boolean enabled;
}
