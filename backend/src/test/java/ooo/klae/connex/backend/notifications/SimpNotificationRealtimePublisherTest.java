package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

class SimpNotificationRealtimePublisherTest {

    private final RealtimeRoutingIdentityResolver routingIdentities = new RealtimeRoutingIdentityResolver();

    @Test
    void recycledUsernameIsAddressedOnlyByItsCurrentAccountId() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        User recipient = new User();
        recipient.setId(23);
        recipient.setUsername("recycled");
        when(userMapper.getUserById(23)).thenReturn(recipient);
        RealtimeNotificationPayload payload = RealtimeNotificationPayload.invalidated(1);

        new SimpNotificationRealtimePublisher(template, userMapper, routingIdentities).send(23, payload);

        String destination = routingIdentities.destinationFor(23);
        verify(template).convertAndSendToUser(destination, "/queue/notifications", payload);
        verifyNoMoreInteractions(template);
        assertNotEquals("recycled", destination);
        assertNotEquals("uid:23", destination);
        assertNotEquals("23", destination);
    }

    @Test
    void missingAccountPublishesNothing() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);

        new SimpNotificationRealtimePublisher(template, userMapper, routingIdentities)
                .send(23, RealtimeNotificationPayload.invalidated(1));

        verifyNoInteractions(template);
    }
}
