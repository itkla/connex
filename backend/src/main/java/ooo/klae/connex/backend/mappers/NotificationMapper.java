package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.OpenDealRecipient;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.NotificationCountsDto;

/**
 * Mapper for notification inbox lifecycle and reminder reconciliation.
 */
public interface NotificationMapper {
    List<Notification> findPage(
        @Param("recipientId") int recipientId,
        @Param("state") String state,
        @Param("category") String category,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    long countPage(
        @Param("recipientId") int recipientId,
        @Param("state") String state,
        @Param("category") String category,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId
    );

    NotificationCountsDto getUnreadCounts(@Param("recipientId") int recipientId);

    Notification findById(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markRead(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markUnread(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int dismiss(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int restore(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int snooze(
        @Param("recipientId") int recipientId,
        @Param("id") int id,
        @Param("snoozedUntil") String snoozedUntil
    );

    int markAllRead(@Param("recipientId") int recipientId);

    int upsert(Notification notification);

    /**
     * Slim projection of an existing notification matched by dedupe key: only
     * {@code id}, {@code severity} and {@code resolvedAt} are populated — enough to
     * classify a re-delivery as brand-new, materially changed, or an idempotent
     * no-op. Returns {@code null} when no row matches.
     */
    Notification findByDedupe(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("dedupeKey") String dedupeKey
    );

    List<Notification> findReminderNotifications(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId
    );

    List<Notification> findWorkspaceReminderNotifications(@Param("workspaceId") int workspaceId);

    int resolveReminder(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id,
        @Param("resolvedAt") String resolvedAt
    );

    List<TaskReminderCandidate> findTaskReminderCandidates(@Param("workspaceId") int workspaceId);

    List<DealReminderCandidate> findDealReminderCandidates(@Param("workspaceId") int workspaceId);

    /**
     * Projects deal stakeholders eligible for a relationship-decay nudge: one row per
     * (open deal, stakeholder contact, recipient), where the recipient is the deal's owner or a
     * collaborator. Only contacts owned by the deal's own workspace are projected — stakeholders
     * shared in from another workspace are deliberately excluded so the nudge stays within the
     * workspace's native contacts; broadening to shared stakeholders is deferred.
     */
    List<RelationshipNudgeCandidate> findRelationshipNudgeCandidates(@Param("workspaceId") int workspaceId);

    /**
     * Values of the workspace's open, owned deals — the same universe the nudge candidates draw
     * from — used to derive the high-value threshold that weights a nudge's priority.
     */
    List<Double> findOpenDealValues(@Param("workspaceId") int workspaceId);

    /**
     * One row per (open, owned deal, recipient) where the recipient is the deal owner or a
     * collaborator and a current workspace member. The deal-risk pass joins these against computed
     * risk to emit per-recipient notifications.
     */
    List<OpenDealRecipient> findOpenDealRecipients(@Param("workspaceId") int workspaceId);

    List<Integer> findWorkspaceIds();

    List<Integer> findWorkspaceRecipientIds(@Param("workspaceId") int workspaceId);

    int purgeReminderHistory(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("cutoff") String cutoff
    );

    int purgeWorkspaceReminderHistory(
        @Param("workspaceId") int workspaceId,
        @Param("cutoff") String cutoff
    );

    /**
     * Deletes every notification addressed to a recipient in one workspace.
     * Offboarding replacement for the {@code notification -> workspace_member}
     * CASCADE (#440 increment 3); called when a membership is removed.
     */
    void deleteAllForRecipient(@Param("workspaceId") int workspaceId, @Param("recipientId") int recipientId);

    /**
     * Deletes every notification addressed to a recipient across all workspaces.
     * Offboarding replacement for the DB-level cascade chain on account deletion
     * (#440 increment 3); spans workspaces the user has already left.
     */
    void deleteAllForRecipientAnywhere(@Param("recipientId") int recipientId);

    /**
     * Nulls the actor reference on every notification produced by a user.
     * Offboarding replacement for the {@code notification.actor_id} ON DELETE
     * SET NULL (#440 increment 3).
     */
    void clearActorAnywhere(@Param("userId") int userId);

}
