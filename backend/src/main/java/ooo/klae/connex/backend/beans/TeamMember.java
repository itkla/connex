package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A user's seat and role within one workspace-scoped team. */
@Data
@NoArgsConstructor
public class TeamMember {
    private int workspaceId;
    private int teamId;
    private int userId;
    private String role;
    private String createdAt;
}
