package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealReminderCandidate;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.TaskReminderCandidate;
import ooo.klae.connex.backend.dto.NotificationCountsDto;

/**
 * Mapper for notification inbox lifecycle and reminder reconciliation.
 */
public interface NotificationMapper {
    List<Notification> findPage(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("state") String state,
        @Param("category") String category,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    long countPage(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("state") String state,
        @Param("category") String category,
        @Param("contextType") String contextType,
        @Param("contextId") Integer contextId
    );

    NotificationCountsDto getUnreadCounts(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId
    );

    Notification findById(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markRead(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markUnread(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int dismiss(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int restore(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("id") int id
    );

    int markAllRead(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId
    );

    int upsert(Notification notification);

    List<Notification> findReminderNotifications(
        @Param("workspaceId") int workspaceId,
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

    List<Integer> findWorkspaceIds();

    List<Integer> findWorkspaceRecipientIds(@Param("workspaceId") int workspaceId);

    int purgeReminderHistory(
        @Param("workspaceId") int workspaceId,
        @Param("recipientId") int recipientId,
        @Param("cutoff") String cutoff
    );
}