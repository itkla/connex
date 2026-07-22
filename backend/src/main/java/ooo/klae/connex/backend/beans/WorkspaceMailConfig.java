package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.ToString;

/**
 * A workspace's own SMTP transport, overriding the instance default for
 * workspace-scoped mail (invites). The password is stored encrypted at rest
 * ({@code password_enc}); the raw value never leaves the service layer.
 */
@Data
@ToString(exclude = "passwordEnc")
public class WorkspaceMailConfig {
    private int workspaceId;
    private boolean enabled;
    private String host;
    private Integer port;
    private String username;
    private String passwordEnc;
    private String fromAddress;
    private String fromName;
    private boolean starttls;
    private boolean ssl;
    private boolean auth;
    private String updatedAt;
}
