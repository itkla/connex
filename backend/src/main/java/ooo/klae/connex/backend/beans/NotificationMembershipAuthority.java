package ooo.klae.connex.backend.beans;

/**
 * Current exact-membership projection retained under a transaction's own control-plane lock.
 * Reserved for NotificationMapper conversion (#485 L9b); a workspace/user pair alone cannot
 * distinguish departure followed by rejoin. Instances are authority only while their lock is held.
 */
public record NotificationMembershipAuthority(
        int workspaceId, int recipientId, String status, long membershipId) {
}
