package ooo.klae.connex.backend.notifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

class SimpNotificationRealtimePublisherTest {
    @Test
    void recycledUsernameIsAddressedOnlyByItsCurrentAccountId() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        User recipient = new User();
        recipient.setId(23);
        recipient.setUsername("recycled");
        when(userMapper.getUserById(23)).thenReturn(recipient);
        RealtimeNotificationPayload payload = RealtimeNotificationPayload.invalidated(1);

        new SimpNotificationRealtimePublisher(template, userMapper).send(23, payload);

        verify(template).convertAndSendToUser("uid:23", "/queue/notifications", payload);
    }

    @Test
    void missingAccountPublishesNothing() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);

        new SimpNotificationRealtimePublisher(template, userMapper)
                .send(23, RealtimeNotificationPayload.invalidated(1));

        verifyNoInteractions(template);
    }
}
