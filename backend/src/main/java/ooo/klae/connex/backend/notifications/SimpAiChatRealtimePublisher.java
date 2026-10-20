package ooo.klae.connex.backend.notifications;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
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
    private final AiAssistantPrivacyPublicationGuard publicationGuard;
    private final AiChatPrivacyGuard privacyGuard;
    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final AiRestrictionEpoch restrictionEpoch;

    @Override
    public void sendUser(int userId, AiChatStepFrameDto frame) {
        tenantWorkScope.inWorkspace(frame.workspaceId(), () -> {
            long epoch = restrictionEpoch.current(frame.workspaceId());
            restrictionEpoch.runIfCurrent(frame.workspaceId(), epoch, () -> {
                var source = chatMapper.getTurnById(frame.workspaceId(), frame.sessionId(), frame.turnId(), privacyGuard.currentGeneration(frame.workspaceId()));
                if (source == null || source.getMissionRunId() != null
                        || !Objects.equals(source.getRequestedByUserId(), userId)
                        || source.getPrivacyGeneration() == null
                        || (frame.text() != null && !"running".equals(source.getStatus()))
                        || ("terminal".equals(frame.kind()) && !Objects.equals(source.getStatus(), frame.status()))
                        || !canReceive(userId, frame)) {
                    return;
                }
                privacyGuard.requireCurrent(chatMapper, new AiChatPrivacyToken(frame.workspaceId(),
                        frame.sessionId(), source.getPrivacyGeneration(), source.getPrivacyTaintSeq(), privacyGuard.organizationId(frame.workspaceId()), source.getOrganizationPrivacyGeneration()));
                publicationGuard.publish(privacyGuard.organizationId(frame.workspaceId()),
                        source.getOrganizationPrivacyGeneration(), () -> messagingTemplate.convertAndSendToUser(
                                routingIdentities.destinationFor(userId), AI_CHAT_QUEUE, frame,
                                AiChatPrivacyOutboundInterceptor.headers(privacyGuard.organizationId(frame.workspaceId()),
                                        source.getOrganizationPrivacyGeneration())));
            });
        });
    }

    @Override
    public void sendSession(int workspaceId, int sessionId, AiChatStepFrameDto frame) {
        if (frame.workspaceId() != workspaceId || frame.sessionId() != sessionId
                || frame.text() != null || frame.tool() != null || frame.toolCallId() != null) {
            return;
        }
        tenantWorkScope.inWorkspace(workspaceId, () -> {
            long epoch = restrictionEpoch.current(workspaceId);
            restrictionEpoch.runIfCurrent(workspaceId, epoch, () -> {
                var session = chatMapper.getPrivacySession(workspaceId, sessionId);
                if (session == null) {
                    return;
                }
                var sourceToken = privacyGuard.capture(chatMapper, session);
                List<Integer> recipientIds = recipientReader.recipients(workspaceId, sessionId);
                for (Integer userId : recipientIds) {
                    if (canReceive(userId, frame)) {
                        publicationGuard.publish(sourceToken.organizationId(), sourceToken.organizationPrivacyGeneration(),
                                () -> messagingTemplate.convertAndSendToUser(
                                        routingIdentities.destinationFor(userId), AI_CHAT_QUEUE, frame,
                                        AiChatPrivacyOutboundInterceptor.headers(sourceToken.organizationId(), sourceToken.organizationPrivacyGeneration())));
                    }
                }
            });
        });
    }

    @Override
    public void invalidateWorkspace(int workspaceId, long cutoffGeneration) {
        if (cutoffGeneration < 0) {
            throw new IllegalArgumentException("Invalid privacy cutoff");
        }
        tenantWorkScope.inWorkspace(workspaceId, () -> {
            var frame = new PrivacyInvalidated(workspaceId, "privacy_invalidated", Long.toString(cutoffGeneration));
            int after = 0;
            while (true) {
                List<Integer> candidates = userMapper.activeAiChatRecipientIdsAfter(workspaceId, after, 100);
                if (candidates.isEmpty()) {
                    return;
                }
                for (int userId : candidates) {
                    if (workspaceService.isMember(workspaceId, userId)
                            && canReceivePrivacyMetadata(workspaceId, userId)
                            && userMapper.getActiveAiChatRealtimeRecipientsByIds(workspaceId, List.of(userId)).stream()
                                .anyMatch(recipient -> Objects.equals(recipient.id(), userId))) {
                        messagingTemplate.convertAndSendToUser(
                                routingIdentities.destinationFor(userId), AI_CHAT_QUEUE, frame);
                    }
                }
                after = candidates.getLast();
            }
        });
    }

    private boolean canReceivePrivacyMetadata(int workspaceId, int userId) {
        var permissions = workspaceService.permissionsFor(workspaceId, userId);
        return permissions.contains(Permission.AI_USE) || permissions.contains(Permission.AI_SESSION_ADMIN);
    }

    private boolean canReceive(int userId, AiChatStepFrameDto frame) {
        return workspaceService.isMember(frame.workspaceId(), userId)
                && workspaceService.permissionsFor(frame.workspaceId(), userId).contains(Permission.AI_USE)
                && chatMapper.getAccessibleSessionById(frame.workspaceId(), userId, frame.sessionId(), privacyGuard.currentGeneration(frame.workspaceId())) != null
                && userMapper.getActiveAiChatRealtimeRecipientsByIds(frame.workspaceId(), List.of(userId)).stream()
                    .anyMatch(recipient -> Objects.equals(recipient.id(), userId));
    }
}
