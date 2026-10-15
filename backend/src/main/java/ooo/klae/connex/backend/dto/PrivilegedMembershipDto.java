package ooo.klae.connex.backend.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The current account's organization authority and privileged workspace exits in that organization. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrivilegedMembershipDto(
        int orgId, String orgName, String orgRole, List<Workspace> workspaces) {
    /** A privileged active workspace membership the account may attempt to leave. */
    public record Workspace(int workspaceId, String workspaceName) {}
}
