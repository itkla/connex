package ooo.klae.connex.backend.notifications;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
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
    private final RealtimeRoutingIdentityResolver routingIdentities;
    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final AiRestrictionEpoch restrictionEpoch;

    @Override
    public void sendUser(int userId, AiChatStepFrameDto frame) {
        tenantWorkScope.inWorkspace(frame.workspaceId(), () -> {
            long epoch = restrictionEpoch.current(frame.workspaceId());
            restrictionEpoch.runIfCurrent(frame.workspaceId(), epoch, () -> {
                var source = chatMapper.getTurnById(frame.workspaceId(), frame.sessionId(), frame.turnId());
                if (source == null || source.getMissionRunId() != null
                        || !Objects.equals(source.getRequestedByUserId(), userId)
                        || source.getPrivacyGeneration() == null
                        || (frame.text() != null && !"running".equals(source.getStatus()))
                        || ("terminal".equals(frame.kind()) && !Objects.equals(source.getStatus(), frame.status()))
                        || !canReceive(userId, frame)) {
                    return;
                }
                AiChatPrivacyGuard.requireCurrent(chatMapper, new AiChatPrivacyToken(frame.workspaceId(),
                        frame.sessionId(), source.getPrivacyGeneration(), source.getPrivacyTaintSeq()));
                messagingTemplate.convertAndSendToUser(
                        routingIdentities.destinationFor(userId), AI_CHAT_QUEUE, frame);
            });
        });
    }

    @Override
    public void sendSession(int workspaceId, int sessionId, AiChatStepFrameDto frame) {
        if (frame.text() != null || frame.tool() != null || frame.toolCallId() != null) {
            return;
        }
        tenantWorkScope.inWorkspace(workspaceId, () -> {
            long epoch = restrictionEpoch.current(workspaceId);
            restrictionEpoch.runIfCurrent(workspaceId, epoch, () -> {
                List<Integer> recipientIds = recipientReader.recipients(workspaceId, sessionId);
                for (Integer userId : recipientIds) {
                    if (canReceive(userId, frame)) {
                        messagingTemplate.convertAndSendToUser(
                                routingIdentities.destinationFor(userId), AI_CHAT_QUEUE, frame);
                    }
                }
            });
        });
    }

    private boolean canReceive(int userId, AiChatStepFrameDto frame) {
        return workspaceService.isMember(frame.workspaceId(), userId)
                && workspaceService.permissionsFor(frame.workspaceId(), userId).contains(Permission.AI_USE)
                && chatMapper.getAccessibleSessionById(frame.workspaceId(), userId, frame.sessionId()) != null
                && userMapper.getActiveAiChatRealtimeRecipientsByIds(frame.workspaceId(), List.of(userId)).stream()
                    .anyMatch(recipient -> Objects.equals(recipient.id(), userId));
    }
}
