package ooo.klae.connex.backend.beans;

/**
 * Unfiltered control projection for the forthcoming NotificationMapper conversion (#485 L9b).
 * Lifecycle facts authorize only the explicit workspace filter, not ordinary inbox membership.
 */
public record NotificationMembership(
        int workspaceId,
        int recipientId,
        String status,
        long membershipId,
        int orgId,
        String workspaceName,
        String workspaceLifecycleState,
        String organizationLifecycleState) {

    /** Preserves active membership and the content-free pending invitation exception. */
    public boolean admits(String notificationType) {
        return "active".equals(status)
            || "pending".equals(status) && "workspace.join".equals(notificationType);
    }

    /** Matches WorkspaceMapper.getRole's membership and lifecycle admission. */
    public boolean permitsWorkspaceFilter() {
        return "active".equals(status)
            && "active".equals(workspaceLifecycleState)
            && "active".equals(organizationLifecycleState);
    }
}
