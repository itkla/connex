package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A workspace-scoped team used as the canonical grouping primitive. */
@Data
@NoArgsConstructor
public class Team {
    private int id;
    private int workspaceId;
    private String name;
    private String description;
    private Integer managerUserId;
    private String archivedAt;
    private String createdAt;
    private String updatedAt;
}
