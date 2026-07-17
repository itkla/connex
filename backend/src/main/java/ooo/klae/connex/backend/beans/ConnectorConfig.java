package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A workspace's third-party marketing audience-sync connector selection. The push credential lives in
 * the central secret store; this bean carries only its opaque reference and masked metadata plus the
 * external list identifier the connector pushes into.
 */
@Data
@NoArgsConstructor
@ToString(exclude = {"credentialRef"})
public class ConnectorConfig {
    private int id;
    private int workspaceId;
    private String connector;
    private String endpoint;
    private String externalListId;
    private String credentialRef;
    private String credentialLast4;
    private boolean enabled;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
