package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * Upsert of a workspace's delivery provider settings for one channel. Structural constraints only;
 * the per-provider validation, SSRF vetting, and credential-encryption rules live in
 * {@code DeliveryProviderConfigService}. A null or blank {@code apiKey} leaves any stored send
 * credential unchanged when the provider is unchanged; a blank value is never persisted.
 */
@Data
@ToString(exclude = "apiKey")
public class DeliveryProviderConfigRequest {

    @NotBlank
    @Size(max = 16)
    private String channel;

    @NotBlank
    @Size(max = 32)
    private String provider;

    @Size(max = 2048)
    private String endpoint;

    @Email
    @Size(max = 320)
    private String fromAddress;

    @Size(max = 255)
    private String fromName;

    @Size(max = 512)
    private String apiKey;

    private boolean enabled;
}
