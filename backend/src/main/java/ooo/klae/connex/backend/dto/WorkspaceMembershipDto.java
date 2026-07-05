package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace the current user belongs to, with their role in it and the organization it belongs to.
 * Powers the workspace switcher, the {@code GET /api/workspaces} listing, and lets the client resolve
 * the active organization ({@code orgId}) and the user's org role ({@code orgRole}, null when the user
 * is not an org administrator) for the organization admin area.
 */
@Data
@NoArgsConstructor
public class WorkspaceMembershipDto {
    private int id;
    private String name;
    private String slug;
    private String role;
    private int orgId;
    private String orgName;
    private String orgRole;

    public WorkspaceMembershipDto(int id, String name, String slug, String role) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.role = role;
    }
}
