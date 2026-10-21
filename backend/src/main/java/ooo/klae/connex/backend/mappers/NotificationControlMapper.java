package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.NotificationMembership;
import ooo.klae.connex.backend.beans.NotificationMembershipAuthority;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * Physical control-plane contracts reserved for NotificationMapper conversion (#485 L9b).
 * Existing NotificationMapper statements remain authoritative until that conversion. Locking
 * statements route on the caller's transaction connection and never suspend that transaction.
 */
public interface NotificationControlMapper {
    /** Loads all recipient memberships, including pending and inactive lifecycle roots. */
    List<NotificationMembership> findRecipientMemberships(@Param("recipientId") int recipientId);

    /** Loads unfiltered workspace sets for a trusted, nonempty batch of recipient organizations. */
    List<Workspace> findOrganizationWorkspaces(@Param("orgIds") List<Integer> orgIds);

    /** Retains the exact membership without adding workspace or organization locks. */
    NotificationMembershipAuthority lockMembershipForShare(
        @Param("workspaceId") int workspaceId, @Param("recipientId") int recipientId);

    /** Retains the exact membership exclusively at the caller's existing lock-order position. */
    NotificationMembershipAuthority lockMembershipForUpdate(
        @Param("workspaceId") int workspaceId, @Param("recipientId") int recipientId);

    /** Retains a live actor through commit; a missing actor is projected as null by the caller. */
    Integer lockActorForShare(@Param("actorId") int actorId);
}
