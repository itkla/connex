package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A user's customized dashboard layout — the ordered set of widgets, their spans and
 * visibility, stored as an opaque JSON blob owned by the client. Personal to the owning
 * user within a workspace (one row per {@code (workspace_id, user_id)}); the backend stores
 * and returns {@code layoutJson} verbatim. Mapped via {@code UserDashboardMapper} /
 * {@code UserDashboardMapper.xml}.
 */
@Data
@NoArgsConstructor
public class UserDashboard {
    private int id;
    private int workspaceId;
    private int userId;
    private String layoutJson;
    private String createdAt;
    private String updatedAt;
}
