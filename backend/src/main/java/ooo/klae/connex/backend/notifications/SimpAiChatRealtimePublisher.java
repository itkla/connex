package ooo.klae.connex.backend.notifications;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.AiChatRealtimeRecipientDto;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Local simple-broker publisher for explicitly authorized assistant user destinations. */
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
    private final TenantWorkScope tenantWorkScope;
    private final AiChatRealtimeRecipientReader recipientReader;

    @Override
    public void sendUser(int userId, AiChatStepFrameDto frame) {
        User recipient = userMapper.getUserById(userId);
        if (recipient == null || recipient.getUsername() == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(recipient.getUsername(), AI_CHAT_QUEUE, frame);
    }

    @Override
    public void sendSession(int workspaceId, int sessionId, AiChatStepFrameDto frame) {
        List<Integer> recipientIds = tenantWorkScope.inWorkspace(
                workspaceId,
                () -> recipientReader.recipients(workspaceId, sessionId));
        if (recipientIds.isEmpty()) {
            return;
        }
        userMapper.getActiveAiChatRealtimeRecipientsByIds(workspaceId, recipientIds).stream()
                .map(AiChatRealtimeRecipientDto::username)
                .filter(username -> username != null && !username.isBlank())
                .forEach(username -> messagingTemplate.convertAndSendToUser(
                        username, AI_CHAT_QUEUE, frame));
    }
}
