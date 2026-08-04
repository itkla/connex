package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Organization-level authority membership disclosed to an organization administrator. */
@Data
@NoArgsConstructor
public class OrganizationLayoutAuthorityMemberDto {
    private int userId;
    private String displayName;
    private String profilePictureUrl;
    private String orgRole;
}
