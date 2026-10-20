package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/** Resolves authorization immediately before sending metadata-only requester invalidations. */
@Component
@RequiredArgsConstructor
public class AiAgentRealtimePublisher {
    private final ObjectProvider<SimpMessagingTemplate> messaging;
    private final AiAssistantPrivacyPublicationGuard publicationGuard;
    private final AiChatPrivacyGuard privacyGuard;
    private final AiAgentRunMapper runs;
    private final AiChatMapper chat;
    private final UserMapper users;
    private final WorkspaceService workspaces;
    private final RealtimeRoutingIdentityResolver identities;
    private final AiRestrictionEpoch restriction;

    /** Source identity only; no model prose, selected record, counts, or reason enters the frame. */
    public record Frame(int workspaceId, int sessionId, int runId, String stateVersion,
            String kind, String coarseState) {}

    private boolean supportedContent(AiAgentRun run) {
        try {
            var codec = new AiAgentContinuationCodec();
            var snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    /** Invoked after durable transactions return; privacy and revocation races are fenced at send. */
    public void publish(AiAgentRun source, String kind) {
        if (!Set.of("mission_created", "mission_state", "mission_output").contains(kind)) {
            throw new IllegalArgumentException("Unknown mission frame");
        }
        int workspaceId = source.getWorkspaceId();
        int id = source.getId();
        long version = source.getStateVersion();
        long generation = source.getPrivacyGeneration();
        long epoch = restriction.current(workspaceId);
        restriction.runIfCurrent(workspaceId, epoch, () -> {
            AiAgentRun current = runs.getRun(workspaceId, id);
            var barrier = chat.getPrivacyBarrier(workspaceId);
            if (current == null || current.getSessionId() == null || current.getOwnerUserId() == null
                    || current.getStateVersion() != version || current.getPrivacyGeneration() != generation
                    || !supportedContent(current) || current.getWithheldAt() != null || barrier == null || barrier.isBlocked()
                    || barrier.getGeneration() != generation) {
                return;
            }
            int owner = current.getOwnerUserId();
            var session = chat.getAccessibleSessionById(workspaceId, owner, current.getSessionId(), privacyGuard.currentGeneration(workspaceId));
            if (!workspaces.isMember(workspaceId, owner)
                    || !workspaces.permissionsFor(workspaceId, owner).contains(Permission.AI_USE)
                    || session == null || session.getCreatedByUserId() == null
                    || !workspaces.isMember(workspaceId, session.getCreatedByUserId())
                    || users.getActiveAiChatRealtimeRecipientsByIds(workspaceId, List.of(owner)).stream()
                        .noneMatch(user -> Objects.equals(user.id(), owner))) {
                return;
            }
            SimpMessagingTemplate template = messaging.getIfAvailable();
            if (template != null) {
                publicationGuard.publish(privacyGuard.organizationId(workspaceId), source.getOrganizationPrivacyGeneration(),
                        () -> template.convertAndSendToUser(identities.destinationFor(owner), "/queue/ai-chat",
                                new Frame(workspaceId, current.getSessionId(), id, Long.toString(version), kind, current.getStatus())));
            }
        });
    }
}
