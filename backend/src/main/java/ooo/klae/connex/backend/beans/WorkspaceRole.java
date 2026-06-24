package ooo.klae.connex.backend.beans;

import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An owner-defined custom role and the permission catalog keys it grants.
 */
@Data
@NoArgsConstructor
public class WorkspaceRole {
    private int id;
    private int workspaceId;
    private String name;
    private List<String> permissions;
    private String createdAt;
    private String updatedAt;
}
