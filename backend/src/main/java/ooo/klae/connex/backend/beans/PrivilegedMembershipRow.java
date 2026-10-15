package ooo.klae.connex.backend.beans;

/** One privileged membership source, restricted to the account whose requirements are read. */
public record PrivilegedMembershipRow(
        int orgId, String orgName, String orgRole, Integer workspaceId, String workspaceName) {}
