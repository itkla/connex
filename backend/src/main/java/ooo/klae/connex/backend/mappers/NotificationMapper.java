package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.HistoricalNotificationBaseline;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.OpenDealRecipient;
import ooo.klae.connex.backend.beans.RelationshipNudgeCandidate;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.NotificationCountsDto;

/**
 * Mapper for notification inbox lifecycle and reminder reconciliation.
 */
public interface NotificationMapper {
    default List<Notification> findPage(
        int recipientId,
        String status,
        String category,
        String contextType,
        Integer contextId,
        int limit,
        int offset
    ) {
        return findPage(
            recipientId,
            status,
            null,
            category == null ? null : List.of(category),
            null,
            null,
            contextType,
            contextId,
            getDatabaseUtcTimestamp(),
            limit,
            offset
        );
    }

    List<Notification> findPage(
        @Param("recipientId") int recipientId,
        @Param("status") String status,
        @Param("types") List<String> types,
        @Param("categories") List<String> categories,
        @Param("severities") List<String> severities,
        @Param("workspaceId") Integer workspaceId,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId,
        @Param("asOf") String asOf,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    default List<Notification> findPage(
        int recipientId,
        String status,
        String category,
        String contextType,
        Integer contextId,
        String asOf,
        int limit,
        int offset
    ) {
        return findPage(
            recipientId,
            status,
            null,
            category == null ? null : List.of(category),
            null,
            null,
            contextType,
            contextId,
            asOf,
            limit,
            offset
        );
    }

    default long countPage(
        int recipientId,
        String status,
        String category,
        String contextType,
        Integer contextId
    ) {
        return countPage(
            recipientId,
            status,
            null,
            category == null ? null : List.of(category),
            null,
            null,
            contextType,
            contextId,
            getDatabaseUtcTimestamp()
        );
    }

    long countPage(
        @Param("recipientId") int recipientId,
        @Param("status") String status,
        @Param("types") List<String> types,
        @Param("categories") List<String> categories,
        @Param("severities") List<String> severities,
        @Param("workspaceId") Integer workspaceId,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId,
        @Param("asOf") String asOf
    );

    default long countPage(
        int recipientId,
        String status,
        String category,
        String contextType,
        Integer contextId,
        String asOf
    ) {
        return countPage(
            recipientId,
            status,
            null,
            category == null ? null : List.of(category),
            null,
            null,
            contextType,
            contextId,
            asOf
        );
    }

    NotificationCountsDto getUnreadCounts(
        @Param("recipientId") int recipientId,
        @Param("asOf") String asOf
    );

    default NotificationCountsDto getUnreadCounts(int recipientId) {
        return getUnreadCounts(recipientId, getDatabaseUtcTimestamp());
    }

    List<FacetCount> countsByCategory(@Param("recipientId") int recipientId);

    List<FacetCount> countsBySeverity(@Param("recipientId") int recipientId);

    List<FacetCount> countsByWorkspace(@Param("recipientId") int recipientId);

    String getNextSnoozeExpiry(
        @Param("recipientId") int recipientId,
        @Param("asOf") String asOf
    );

    long getStateVersion(@Param("recipientId") int recipientId);

    int bumpStateVersions(@Param("recipientIds") List<Integer> recipientIds);

    List<Integer> lockRecipientMemberships(@Param("recipientId") int recipientId);

    long getInboxCutoffId(@Param("recipientId") int recipientId);

    String getDatabaseUtcTimestamp();

    Notification findById(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    Notification findByIdForUpdate(
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

    default int snooze(int recipientId, int id, String snoozedUntil) {
        return snooze(recipientId, id, snoozedUntil, "UTC");
    }

    int snooze(
        @Param("recipientId") int recipientId,
        @Param("id") int id,
        @Param("snoozedUntil") String snoozedUntil,
        @Param("snoozeTimezone") String snoozeTimezone
    );

    int unsnooze(
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markAllRead(
        @Param("recipientId") int recipientId,
        @Param("cutoffId") long cutoffId,
        @Param("readAt") String readAt
    );

    int upsert(Notification notification);

    int claimEmailDelivery(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("dedupeKey") String dedupeKey
    );

    /**
     * Returns an existing notification matched by dedupe key so delivery can
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

    List<HistoricalNotificationBaseline> findHistoricalNotificationBaselines(
        @Param("workspaceId") int workspaceId
    );

    int insertHistoricalNotificationBaselines(
        @Param("workspaceId") int workspaceId,
        @Param("baselines") List<HistoricalNotificationBaseline> baselines
    );

    int deleteHistoricalNotificationBaselines(
        @Param("workspaceId") int workspaceId,
        @Param("baselines") List<HistoricalNotificationBaseline> baselines
    );

    int deleteHistoricalNotificationBaselinesForRecipient(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId
    );

    int deleteHistoricalNotificationBaselinesForRecipientAnywhere(
        @Param("recipientId") int recipientId
    );

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
     * collaborator. Stakeholders may be owned by the deal workspace or actively shared into it,
     * with the same-organization ceiling enforced by the projection.
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

    List<Integer> findWorkspaceRecipientIds(@Param("workspaceId") int workspaceId);

    List<Integer> findPurgeRecipientIds(
        @Param("workspaceId") int workspaceId,
        @Param("cutoff") String cutoff
    );

    List<Integer> findRecipientIdsByActor(@Param("userId") int userId);

    List<Integer> lockRecipientIdsByActor(@Param("userId") int userId);

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
     * CASCADE (#440 increment 3). Deliberately separate from
     * {@link #deleteAllForRecipientAnywhere(int)}: the primitive
     * {@code workspaceId} keeps the workspace-scoped call unable to widen
     * silently, since both statements are exempt from the tenant backstops.
     */
    void deleteAllForRecipient(@Param("workspaceId") int workspaceId, @Param("recipientId") int recipientId);

    /**
     * Deletes every notification addressed to a recipient across all
     * workspaces — account deletion only, spanning workspaces the user
     * already left. Offboarding replacement for the app_user cascade chain
     * (#440 increment 3).
     */
    void deleteAllForRecipientAnywhere(@Param("recipientId") int recipientId);

    /**
     * Nulls the actor reference on every notification produced by a user.
     * Offboarding replacement for the {@code notification.actor_id} ON DELETE
     * SET NULL (#440 increment 3).
     */
    int clearActorAnywhere(@Param("userId") int userId);
}
