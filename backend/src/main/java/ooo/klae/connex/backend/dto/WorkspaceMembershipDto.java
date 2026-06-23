package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace the current user belongs to, with their role in it.
 * Powers the workspace switcher and the {@code GET /api/workspaces} listing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMembershipDto {
    private int id;
    private String name;
    private String slug;
    private String role;
}
