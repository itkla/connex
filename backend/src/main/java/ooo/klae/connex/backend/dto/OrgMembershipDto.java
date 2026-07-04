package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An organization the current user administers, with their org role. Powers the
 * caller's org listing (which orgs they can manage).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgMembershipDto {
    private int id;
    private String name;
    private String slug;
    private String orgRole;
}
