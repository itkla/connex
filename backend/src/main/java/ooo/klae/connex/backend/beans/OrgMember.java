package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An organization membership: a user with org-level authority (owner or admin)
 * over an {@link Organization}. Distinct from workspace membership — org members
 * administer org-scoped configuration (SSO today; billing and domains later),
 * not workspace records.
 */
@Data
@NoArgsConstructor
public class OrgMember {
    private int orgId;
    private int userId;
    private String orgRole;
    private String createdAt;
}
