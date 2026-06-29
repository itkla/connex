package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
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

    int markAllRead(@Param("recipientId") int recipientId);

    int upsert(Notification notification);

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
}