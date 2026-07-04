package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An organization member with their org role, for the org administrator view.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgMemberDto {
    private int id;
    private String username;
    private String displayName;
    private String email;
    private String profilePictureUrl;
    private String orgRole;
}
