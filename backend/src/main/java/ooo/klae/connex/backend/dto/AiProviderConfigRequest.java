package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * Upsert of an organization's BYOP AI provider settings. Conditional validation,
 * credential bundling, and encryption live in the service; a null or blank
 * {@code secretAccessKey} preserves any stored credential.
 */
@Data
@ToString(exclude = { "accessKeyId", "secretAccessKey", "sessionToken" })
public class AiProviderConfigRequest {
    @NotBlank
    @Size(max = 32)
    private String provider;

    @NotBlank
    @Size(max = 64)
    private String region;

    @NotBlank
    @Size(max = 128)
    private String modelId;

    @Size(max = 128)
    private String accessKeyId;

    @Size(max = 512)
    private String secretAccessKey;

    @Size(max = 4096)
    private String sessionToken;

    private boolean noTrainingAttested;

    private boolean enabled;
}
