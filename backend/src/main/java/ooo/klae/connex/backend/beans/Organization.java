package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A top-level organization — the tenant, billing, and breach boundary above
 * workspaces. A workspace belongs to exactly one organization.
 */
@Data
@NoArgsConstructor
public class Organization {
    private int id;
    private String name;
    private String slug;
    private long identityVersion;
    private String createdAt;
    private String updatedAt;
}
