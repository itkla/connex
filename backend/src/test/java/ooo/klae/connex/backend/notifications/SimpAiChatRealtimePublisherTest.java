package ooo.klae.connex.backend.notifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.UserMapper;

class SimpAiChatRealtimePublisherTest {
    @Test
    void explicitInitiatingUserIsResolvedToOnlyTheirUserQueue() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setUsername("initiator");
        when(userMapper.getUserById(17)).thenReturn(user);
        var publisher = new SimpAiChatRealtimePublisher(messagingTemplate, userMapper);
        var frame = new AiChatStepFrameDto(7, 13, 31, 2, "step", "get_record", "executed", null);

        publisher.send(17, frame);

        verify(messagingTemplate).convertAndSendToUser("initiator", "/queue/ai-chat", frame);
    }

    @Test
    void missingInitiatingUserPublishesNothing() {
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        UserMapper userMapper = mock(UserMapper.class);
        var publisher = new SimpAiChatRealtimePublisher(messagingTemplate, userMapper);

        publisher.send(17, new AiChatStepFrameDto(
                7, 13, 31, 0, "terminal", null, "failed", "provider_error"));

        verifyNoInteractions(messagingTemplate);
    }
}
