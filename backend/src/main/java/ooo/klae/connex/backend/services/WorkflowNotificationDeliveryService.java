package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/** Retains workflow attribution and recipient membership authority through the notification write. */
@Service
@RequiredArgsConstructor
public class WorkflowNotificationDeliveryService {
    private final WorkspaceService workspaceService;
    private final NotificationDelivery notificationDelivery;

    /** Reuses the actor/workspace prefix and ordered NOWAIT membership union before delivery. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(Notification notification, int attributionUserId) {
        Set<Integer> members = Set.copyOf(List.of(attributionUserId, notification.getRecipientId()));
        var authority = workspaceService.lockedMemberPermissionsAndMemberships(
            notification.getWorkspaceId(), attributionUserId, members);
        if (!authority.activeMembershipIds().containsAll(members)) {
            throw new WorkflowExecutionException(
                WorkflowDiagnosticCode.ACTION_TARGET_MEMBER_UNAVAILABLE.value(),
                "The selected follow-up member is no longer available.", true);
        }
        notificationDelivery.deliver(notification);
    }
}
