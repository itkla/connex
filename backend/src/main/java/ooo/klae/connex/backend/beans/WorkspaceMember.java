package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a user's membership in a workspace.
 */
@Data
@NoArgsConstructor
public class WorkspaceMember {
    private int workspaceId;
    private int userId;
    private String role;
    private Integer roleId;
    private String status;
    private String createdAt;
}
