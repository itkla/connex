package ooo.klae.connex.backend.dto;

import lombok.Data;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;

/**
 * A workspace's SMTP config as returned to the client. The password is never
 * included; {@code hasPassword} reports whether one is stored so the UI can show
 * a "configured" state and leave the field blank to keep it unchanged.
 *
 * <p>{@code defaultPort} is the instance's resolved SMTP port, which a stored {@code null} port
 * inherits. The client must display and resubmit that value rather than a hardcoded literal: the
 * endpoint-binding guard on save compares effective ports, so echoing 587 on an instance configured
 * for another port would look like an endpoint change and reject an unrelated blank-password edit.
 */
@Data
public class MailConfigDto {
    private boolean enabled;
    private String host;
    private Integer port;
    private String username;
    private String fromAddress;
    private String fromName;
    private boolean starttls;
    private boolean ssl;
    private boolean auth;
    private int defaultPort;
    private boolean hasPassword;
    private boolean configured;
    private String updatedAt;

    /**
     * Maps a stored config to its client view, omitting the encrypted password.
     * @param config the stored config, or null when none exists
     * @param defaultPort the instance's resolved SMTP port, inherited by a stored null port
     * @return the DTO (an empty, disabled default when config is null)
     */
    public static MailConfigDto from(WorkspaceMailConfig config, int defaultPort) {
        MailConfigDto dto = new MailConfigDto();
        dto.setDefaultPort(defaultPort);
        if (config == null) {
            dto.setStarttls(true);
            dto.setAuth(true);
            dto.setPort(defaultPort);
            return dto;
        }
        dto.setEnabled(config.isEnabled());
        dto.setHost(config.getHost());
        dto.setPort(config.getPort());
        dto.setUsername(config.getUsername());
        dto.setFromAddress(config.getFromAddress());
        dto.setFromName(config.getFromName());
        dto.setStarttls(config.isStarttls());
        dto.setSsl(config.isSsl());
        dto.setAuth(config.isAuth());
        dto.setHasPassword(config.isAuth() && config.getPasswordEnc() != null && !config.getPasswordEnc().isBlank());
        dto.setConfigured(true);
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }
}
