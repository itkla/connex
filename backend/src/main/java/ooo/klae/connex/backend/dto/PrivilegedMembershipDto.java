package ooo.klae.connex.backend.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The current account's organization authority and privileged workspace exits in that organization.
 * {@code lastOwner} is an advisory read for hiding impossible exits; the locked leave operation
 * remains authoritative and can refuse for additional ownership or assignment safeguards.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrivilegedMembershipDto(
        int orgId, String orgName, String orgRole, boolean lastOwner, List<Workspace> workspaces) {
    /** A privileged active workspace membership, with whether the account is its sole active owner. */
    public record Workspace(int workspaceId, String workspaceName, boolean lastOwner) {}
}
