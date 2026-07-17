package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Workspace-owned normalized contact-channel suppression entry. */
@Data
@NoArgsConstructor
public class SuppressionEntry {
    private int id;
    private int workspaceId;
    private String scope;
    private String channel;
    private String address;
    private Integer personId;
    private String reason;
    private String note;
    private Integer createdById;
    private LocalDateTime createdAt;
}
