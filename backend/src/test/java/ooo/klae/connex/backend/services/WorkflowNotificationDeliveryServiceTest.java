package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

/** Pins the locked recipient check independently of earlier advisory role reads. */
class WorkflowNotificationDeliveryServiceTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final NotificationDelivery delivery = mock(NotificationDelivery.class);
    private final WorkflowNotificationDeliveryService service = new WorkflowNotificationDeliveryService(workspace, delivery);

    @Test
    void departureAfterAnAdvisoryRoleReadProducesAVisibleRefusal() {
        when(workspace.getRole(7, 23)).thenReturn("member");
        assertEquals("member", workspace.getRole(7, 23));
        when(workspace.lockedMemberPermissionsAndMemberships(7, 17, Set.of(17, 23)))
            .thenReturn(new WorkspaceService.LockedMemberPermissionsAndMemberships(Set.of(), Set.of(17)));

        var failure = assertThrows(WorkflowExecutionException.class, () -> service.deliver(notification(), 17));

        assertEquals("action_target_member_unavailable", failure.code());
        verifyNoInteractions(delivery);
    }

    @Test
    void retainsTheOrderedMembershipUnionBeforeWriting() {
        when(workspace.lockedMemberPermissionsAndMemberships(7, 17, Set.of(17, 23)))
            .thenReturn(new WorkspaceService.LockedMemberPermissionsAndMemberships(Set.of(), Set.of(17, 23)));
        Notification notification = notification();

        service.deliver(notification, 17);

        var order = inOrder(workspace, delivery);
        order.verify(workspace).lockedMemberPermissionsAndMemberships(7, 17, Set.of(17, 23));
        order.verify(delivery).deliver(notification);
    }

    @Test
    void membershipContentionDoesNotFallThroughToDelivery() {
        when(workspace.lockedMemberPermissionsAndMemberships(7, 17, Set.of(17, 23)))
            .thenThrow(new ConflictException("Workspace membership is changing; try again"));
        assertThrows(ConflictException.class, () -> service.deliver(notification(), 17));
        verifyNoInteractions(delivery);
    }

    private static Notification notification() {
        Notification notification = new Notification();
        notification.setWorkspaceId(7);
        notification.setRecipientId(23);
        return notification;
    }
}
