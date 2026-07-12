package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;

@ExtendWith(MockitoExtension.class)
class NotificationPushListenerTest {
    @Mock private NotificationRealtimePublisher realtimePublisher;
    @Mock private NotificationMapper notificationMapper;

    @Test
    void pushReadsTheFinalStateVersionAfterCommit() {
        NotificationDto notification = new NotificationDto();
        notification.setId(77);
        when(notificationMapper.getStateVersion(9)).thenReturn(33L);
        NotificationPushListener listener = new NotificationPushListener(
            realtimePublisher, notificationMapper);

        listener.onPush(new NotificationPushEvent(
            9, "created", notification, "note.mention:5:9"));

        ArgumentCaptor<RealtimeNotificationPayload> payload =
            ArgumentCaptor.forClass(RealtimeNotificationPayload.class);
        verify(realtimePublisher).send(org.mockito.ArgumentMatchers.eq(9), payload.capture());
        assertEquals(33L, payload.getValue().stateVersion());
        assertEquals("created", payload.getValue().kind());
    }

    @Test
    void invalidationPushCarriesOnlyTheFinalStateVersion() {
        when(notificationMapper.getStateVersion(9)).thenReturn(34L);
        NotificationPushListener listener = new NotificationPushListener(
            realtimePublisher, notificationMapper);

        listener.onPush(new NotificationPushEvent(9, "invalidated", null, null));

        ArgumentCaptor<RealtimeNotificationPayload> payload =
            ArgumentCaptor.forClass(RealtimeNotificationPayload.class);
        verify(realtimePublisher).send(org.mockito.ArgumentMatchers.eq(9), payload.capture());
        assertEquals("invalidated", payload.getValue().kind());
        assertEquals(34L, payload.getValue().stateVersion());
        assertNull(payload.getValue().notification());
        assertNull(payload.getValue().dedupeKey());
    }
}
