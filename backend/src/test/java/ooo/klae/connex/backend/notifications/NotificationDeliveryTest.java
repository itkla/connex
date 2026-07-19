package ooo.klae.connex.backend.notifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationQuietHours;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.NotificationQuietHoursMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;
import ooo.klae.connex.backend.services.NotificationQuietHoursEvaluator;

/**
 * Verifies delivery routing and realtime classification: in-app always delivers;
 * email delivers only on the first occurrence AND when opted in; a failing channel
 * never blocks another; and a realtime frame is pushed as {@code created} for a
 * brand-new row, {@code updated} for a materially changed one, and not at all for
 * an idempotent re-dispatch or a row withheld from the recipient's inbox.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryTest {

    @Mock private NotificationDispatcher inApp;
    @Mock private NotificationDispatcher email;
    @Mock private NotificationMapper notificationMapper;
    @Mock private PreferenceMapper preferenceMapper;
    @Mock private NotificationPushPublisher pushPublisher;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private NotificationQuietHoursMapper quietHoursMapper;
    @Mock private NotificationQuietHoursEvaluator quietHoursEvaluator;
    @Mock private NotificationQuietHoursBypassPolicy bypassPolicy;

    private NotificationDelivery delivery;

    @BeforeEach
    void setUp() {
        lenient().when(inApp.channel()).thenReturn("in_app");
        lenient().when(email.channel()).thenReturn("email");
        lenient().doAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            if (notification.getId() == 0) {
                notification.setId(77);
            }
            return 1;
        }).when(inApp).dispatch(any());
        lenient().when(notificationMapper.findById(eq(9), anyInt()))
            .thenAnswer(invocation -> existingRow(invocation.getArgument(1), "info", null));
        delivery = new NotificationDelivery(
                List.of(inApp, email), notificationMapper, preferenceMapper, pushPublisher,
                stateVersionService, quietHoursMapper, quietHoursEvaluator, bypassPolicy,
                Clock.fixed(Instant.parse("2026-07-20T02:00:00Z"), ZoneOffset.UTC));
    }

    private Notification notification() {
        Notification n = new Notification();
        n.setWorkspaceId(1);
        n.setRecipientId(9);
        n.setType("note.mention");
        n.setSeverity("info");
        n.setDedupeKey("note.mention:5:9");
        return n;
    }

    private Notification existingRow(int id, String severity, String resolvedAt) {
        Notification n = new Notification();
        n.setId(id);
        n.setType("note.mention");
        n.setSeverity(severity);
        n.setResolvedAt(resolvedAt);
        return n;
    }

    @Test
    void inApp_alwaysDelivers_emailGatedOnOptIn() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(true);

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email).dispatch(n);
    }

    @Test
    void email_notSent_whenNotOptedIn() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(false);

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email, never()).dispatch(any());
    }

    @Test
    void email_notSent_onRepeatOccurrence_butInAppStillDelivers() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey()))
                .thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email, never()).dispatch(any());
    }

    @Test
    void nullDedupeKey_treatedAsFirstOccurrence() {
        Notification n = notification();
        n.setDedupeKey(null);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(true);

        delivery.deliver(n);

        verify(email).dispatch(n);
    }

    @Test
    void inAppFailure_propagates_andSkipsEmail() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        doAnswer(invocation -> {
            throw new RuntimeException("boom");
        }).when(inApp).dispatch(any());

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> delivery.deliver(n));

        verify(email, never()).dispatch(any());
        verify(pushPublisher, never()).created(anyInt(), any(), any());
    }

    @Test
    void emailFailure_isIsolated_andDoesNotPropagate() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(true);
        doAnswer(invocation -> {
            throw new RuntimeException("smtp down");
        }).when(email).dispatch(any());

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email).dispatch(n);
    }

    @Test
    void brandNewRow_pushesCreatedFrame() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Notification>getArgument(0).setId(77);
            return 1;
        }).when(inApp).dispatch(any());
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(pushPublisher).created(eq(9), any(NotificationDto.class), eq("note.mention:5:9"));
        verify(pushPublisher, never()).updated(anyInt(), any(), any());
        verify(stateVersionService).markChangedWithDetailedPush(9);
    }

    @Test
    void escalatedSeverity_pushesUpdatedFrame() {
        Notification n = notification();
        n.setSeverity("warning");
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey()))
                .thenReturn(existingRow(77, "info", null));
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "warning", null));

        delivery.deliver(n);

        verify(pushPublisher).updated(eq(9), any(NotificationDto.class), eq("note.mention:5:9"));
        verify(pushPublisher, never()).created(anyInt(), any(), any());
    }

    @Test
    void revivedResolvedRow_pushesUpdatedFrame() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey()))
                .thenReturn(existingRow(77, "info", "2026-07-01T00:00:00"));
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(pushPublisher).updated(eq(9), any(NotificationDto.class), eq("note.mention:5:9"));
        verify(pushPublisher, never()).created(anyInt(), any(), any());
    }

    @Test
    void changedVisibleContentAdvancesStateAndPushesUpdatedFrame() {
        Notification n = notification();
        n.setTitle("New title");
        Notification existing = existingRow(77, "info", null);
        existing.setTitle("Old title");
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(existing);
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(stateVersionService).markChangedWithDetailedPush(9);
        verify(pushPublisher).updated(eq(9), any(NotificationDto.class), eq("note.mention:5:9"));
    }

    @Test
    void unchangedRedelivery_pushesNothing() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey()))
                .thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(notificationMapper, never()).findById(anyInt(), anyInt());
        verify(pushPublisher, never()).created(anyInt(), any(), any());
        verify(pushPublisher, never()).updated(anyInt(), any(), any());
        verify(stateVersionService, never()).markChangedWithDetailedPush(anyInt());
    }

    @Test
    void staleUnchangedPreReadStillPushesWhenUpsertChangesTheLockedRow() {
        Notification n = notification();
        Notification existing = existingRow(77, "info", null);
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(existing);
        when(inApp.dispatch(n)).thenReturn(2);
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "info", null));

        delivery.deliver(n);

        verify(stateVersionService).markChangedWithDetailedPush(9);
        verify(pushPublisher).updated(eq(9), any(NotificationDto.class), eq("note.mention:5:9"));
    }

    @Test
    void rowWithheldFromInbox_pushesNothing() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Notification>getArgument(0).setId(77);
            return 1;
        }).when(inApp).dispatch(any());
        when(notificationMapper.findById(9, 77)).thenReturn(null);

        delivery.deliver(n);

        verify(pushPublisher, never()).created(anyInt(), any(), any());
        verify(pushPublisher, never()).updated(anyInt(), any(), any());
        verify(stateVersionService).markChanged(9);
        verify(preferenceMapper, never()).isEnabledOptIn(anyInt(), any(), any());
        verify(email, never()).dispatch(any());
    }

    @Test
    void quietHoursKeepInAppAndInvalidationButSuppressDetailAndEmail() {
        Notification n = notification();
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Notification>getArgument(0).setId(77);
            return 1;
        }).when(inApp).dispatch(any());
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "info", null));
        NotificationQuietHours quietHours = new NotificationQuietHours();
        when(quietHoursMapper.findByUserId(9)).thenReturn(quietHours);
        when(quietHoursEvaluator.evaluate(quietHours, Instant.parse("2026-07-20T02:00:00Z")))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(true, null));

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(stateVersionService).markChanged(9);
        verify(pushPublisher, never()).created(anyInt(), any(), any());
        verify(pushPublisher, never()).updated(anyInt(), any(), any());
        verify(email, never()).dispatch(any());
    }

    @Test
    void criticalReminderDoesNotBypassQuietHours() {
        Notification n = notification();
        n.setType("task.due");
        n.setSeverity("critical");
        when(notificationMapper.findByDedupe(1, 9, n.getDedupeKey())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<Notification>getArgument(0).setId(77);
            return 1;
        }).when(inApp).dispatch(any());
        when(notificationMapper.findById(9, 77)).thenReturn(existingRow(77, "critical", null));
        NotificationQuietHours quietHours = new NotificationQuietHours();
        when(quietHoursMapper.findByUserId(9)).thenReturn(quietHours);
        when(quietHoursEvaluator.evaluate(quietHours, Instant.parse("2026-07-20T02:00:00Z")))
            .thenReturn(new NotificationQuietHoursEvaluator.Evaluation(true, null));

        delivery.deliver(n);

        verify(bypassPolicy).bypasses(n);
        verify(stateVersionService).markChanged(9);
        verify(email, never()).dispatch(any());
    }
}
