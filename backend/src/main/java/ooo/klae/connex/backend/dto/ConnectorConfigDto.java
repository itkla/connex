package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import ooo.klae.connex.backend.beans.ConnectorConfig;

/**
 * A workspace's connector settings, as returned to the client. The push credential is never included;
 * only masked metadata and a presence flag are exposed so the settings UI can render a configured
 * state.
 */
@Data
public class ConnectorConfigDto {
    private String connector;
    private String endpoint;
    private String externalListId;
    private boolean hasCredential;
    private String credentialLast4;
    private boolean enabled;
    private LocalDateTime updatedAt;

    /**
     * Maps a stored config to its client view, omitting every secret.
     * @param config the stored config
     * @return the masked DTO
     */
    public static ConnectorConfigDto from(ConnectorConfig config) {
        ConnectorConfigDto dto = new ConnectorConfigDto();
        dto.setConnector(config.getConnector());
        dto.setEndpoint(config.getEndpoint());
        dto.setExternalListId(config.getExternalListId());
        dto.setHasCredential(config.getCredentialRef() != null && !config.getCredentialRef().isBlank());
        dto.setCredentialLast4(config.getCredentialLast4());
        dto.setEnabled(config.isEnabled());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }
}
