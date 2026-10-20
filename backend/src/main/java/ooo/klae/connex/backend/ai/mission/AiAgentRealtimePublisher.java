package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
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
    private final AiAgentRunMapper runs;
    private final AiChatMapper chat;
    private final UserMapper users;
    private final WorkspaceService workspaces;
    private final RealtimeRoutingIdentityResolver identities;
    private final AiRestrictionEpoch restriction;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<AiAgentRealtimePublisher> transactionalSelf;

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
        try {
            transactionalSelf.getObject().publishLocked(source, kind);
        } catch (ooo.klae.connex.backend.exceptions.ForbiddenException
                | ooo.klae.connex.backend.exceptions.ResourceNotFoundException revoked) {
            return;
        }
    }

    /** Retains disclosure authority through the local broker enqueue; transport remains asynchronous. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishLocked(AiAgentRun source, String kind) {
        if (source.getOwnerUserId() == null || source.getSessionId() == null) {
            return;
        }
        int workspaceId = source.getWorkspaceId();
        workspaces.lockAndRequirePermissionsSnapshot(workspaceId,
                Map.of(source.getOwnerUserId(), Set.of(Permission.AI_USE)));
        var lockedSession = chat.getMissionSessionForShare(workspaceId, source.getOwnerUserId(), source.getSessionId());
        AiAgentRun locked = runs.lockRunForShare(workspaceId, source.getId());
        if (lockedSession == null || locked == null || !Objects.equals(locked.getOwnerUserId(), source.getOwnerUserId())
                || !Objects.equals(locked.getSessionId(), source.getSessionId())) {
            return;
        }

        int id = source.getId();
        long version = source.getStateVersion();
        long generation = source.getPrivacyGeneration();
        long epoch = restriction.current(workspaceId);
        restriction.runIfCurrent(workspaceId, epoch, () -> {
            AiAgentRun current = runs.getRun(workspaceId, id);
            var barrier = chat.lockPrivacyBarrierForShare(workspaceId);
            if (current == null || current.getSessionId() == null || current.getOwnerUserId() == null
                    || current.getStateVersion() != version || current.getPrivacyGeneration() != generation
                    || !supportedContent(current) || current.getWithheldAt() != null || barrier == null || barrier.isBlocked()
                    || barrier.getGeneration() != generation) {
                return;
            }
            int owner = current.getOwnerUserId();
            var session = chat.getAccessibleSessionById(workspaceId, owner, current.getSessionId());
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
                template.convertAndSendToUser(identities.destinationFor(owner), "/queue/ai-chat",
                        new Frame(workspaceId, current.getSessionId(), id, Long.toString(version), kind, current.getStatus()));
            }
        });
    }
}
