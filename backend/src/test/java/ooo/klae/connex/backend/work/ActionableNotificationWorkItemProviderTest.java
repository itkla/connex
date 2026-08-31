package ooo.klae.connex.backend.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationWorkItem;
import ooo.klae.connex.backend.dto.NotificationWorkPage;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.services.NotificationService;

@ExtendWith(MockitoExtension.class)
class ActionableNotificationWorkItemProviderTest {
    private static final Instant AS_OF = Instant.parse("2026-08-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);

    @Mock private NotificationService notificationService;

    @Test
    void mapsValidatedDealCloseRowsAndSeverityRanking() {
        Notification notification = notification();
        when(notificationService.findActiveDealCloseWork(7, AS_OF, Set.of(), 25))
            .thenReturn(new NotificationWorkPage(List.of(new NotificationWorkItem(
                notification, TODAY.minusDays(2), "a".repeat(64))), 1, 1, AS_OF));
        ActionableNotificationWorkItemProvider provider = provider();

        var result = provider.load(new WorkItemProviderQuery(
            7, 42, TODAY, AS_OF, Set.of(), 25));

        assertEquals("notification:11", result.items().getFirst().id());
        assertEquals(WorkItemUrgency.critical, result.items().getFirst().urgency());
        assertEquals(2, result.items().getFirst().reason().days());
        assertEquals("/records/deals/5", result.items().getFirst().context().href());
    }

    @Test
    void delegatesSnoozeAndReturnsRecipientStateVersion() {
        SnoozeRequest request = new SnoozeRequest();
        request.setHours(4);
        NotificationDto response = new NotificationDto();
        response.setStateVersion(19);
        when(notificationService.snooze(11, request, "a".repeat(64))).thenReturn(response);

        var result = provider().execute(11, new WorkItemActionCommand(
            WorkItemAction.snooze, "a".repeat(64), request, null, null, null));

        verify(notificationService).snooze(11, request, "a".repeat(64));
        assertEquals(19L, result.notificationStateVersion());
    }

    private ActionableNotificationWorkItemProvider provider() {
        return new ActionableNotificationWorkItemProvider(
            notificationService, Clock.fixed(AS_OF, ZoneOffset.UTC));
    }

    private static Notification notification() {
        Notification notification = new Notification();
        notification.setId(11);
        notification.setSeverity("critical");
        notification.setTitle("Deal overdue");
        notification.setContextType("deal");
        notification.setContextId(5);
        notification.setContextLabel("Renewal");
        notification.setActionUrl("/records/deals/5");
        notification.setTriggeredAt("2026-08-20 00:00:00");
        notification.setUpdatedAt("2026-08-21 00:00:00");
        return notification;
    }
}
