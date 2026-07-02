package ooo.klae.connex.backend.notifications;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PreferenceMapper;

/**
 * Verifies delivery routing: in-app always delivers; email delivers only on the
 * first occurrence AND when opted in; a failing channel never blocks another.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryTest {

    @Mock private NotificationDispatcher inApp;
    @Mock private NotificationDispatcher email;
    @Mock private NotificationMapper notificationMapper;
    @Mock private PreferenceMapper preferenceMapper;

    private NotificationDelivery delivery;

    @BeforeEach
    void setUp() {
        when(inApp.channel()).thenReturn("in_app");
        when(email.channel()).thenReturn("email");
        delivery = new NotificationDelivery(List.of(inApp, email), notificationMapper, preferenceMapper);
    }

    private Notification notification() {
        Notification n = new Notification();
        n.setWorkspaceId(1);
        n.setRecipientId(9);
        n.setType("note.mention");
        n.setDedupeKey("note.mention:5:9");
        return n;
    }

    @Test
    void inApp_alwaysDelivers_emailGatedOnOptIn() {
        Notification n = notification();
        when(notificationMapper.existsByDedupe(1, 9, n.getDedupeKey())).thenReturn(false);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(true);

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email).dispatch(n);
    }

    @Test
    void email_notSent_whenNotOptedIn() {
        Notification n = notification();
        when(notificationMapper.existsByDedupe(1, 9, n.getDedupeKey())).thenReturn(false);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(false);

        delivery.deliver(n);

        verify(inApp).dispatch(n);
        verify(email, never()).dispatch(any());
    }

    @Test
    void email_notSent_onRepeatOccurrence_butInAppStillDelivers() {
        Notification n = notification();
        when(notificationMapper.existsByDedupe(1, 9, n.getDedupeKey())).thenReturn(true);

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
    void inAppFailure_doesNotBlockEmail() {
        Notification n = notification();
        when(notificationMapper.existsByDedupe(1, 9, n.getDedupeKey())).thenReturn(false);
        when(preferenceMapper.isEnabledOptIn(9, "note.mention", "email")).thenReturn(true);
        doThrowOnDispatch(inApp);

        delivery.deliver(n);

        verify(email).dispatch(n);
    }

    private static void doThrowOnDispatch(NotificationDispatcher dispatcher) {
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(dispatcher).dispatch(any());
    }
}
