package ooo.klae.connex.backend.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Local simple-broker publisher for one initiating user's assistant step frames. */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@RequiredArgsConstructor
public class SimpAiChatRealtimePublisher implements AiChatRealtimePublisher {
    private static final String AI_CHAT_QUEUE = "/queue/ai-chat";

    private final SimpMessagingTemplate messagingTemplate;
    private final UserMapper userMapper;

    @Override
    public void send(int initiatingUserId, AiChatStepFrameDto frame) {
        User recipient = userMapper.getUserById(initiatingUserId);
        if (recipient == null || recipient.getUsername() == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), AI_CHAT_QUEUE, frame);
    }
}
