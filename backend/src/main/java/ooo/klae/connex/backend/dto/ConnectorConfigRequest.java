package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.ToString;

/**
 * Upsert of a workspace's third-party connector settings. Structural constraints only; the connector
 * validation, SSRF vetting, and credential-encryption rules live in {@code ConnectorConfigService}. A
 * null or blank {@code apiKey} leaves any stored push credential unchanged; a blank value is never
 * persisted.
 */
@Data
@ToString(exclude = "apiKey")
public class ConnectorConfigRequest {

    @NotBlank
    @Size(max = 32)
    private String connector;

    @Size(max = 2048)
    private String endpoint;

    @Size(max = 255)
    private String externalListId;

    @Size(max = 512)
    private String apiKey;

    private boolean enabled;
}
