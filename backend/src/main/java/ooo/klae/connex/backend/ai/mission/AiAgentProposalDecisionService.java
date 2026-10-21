package ooo.klae.connex.backend.ai.mission;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantProposalExpiredException;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService.LockedDecisionContext;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService.PreliminaryPrincipals;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionResponse;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import tools.jackson.databind.ObjectMapper;

/** Nontransactional selected-item coordinator with truthful partial and uncertain-commit outcomes. */
@Service
@RequiredArgsConstructor
public class AiAgentProposalDecisionService {
    private final AiChatPrivacyGuard privacyGuard;
    private final AiAgentProposalReadService reads;
    private final AiAgentProposalSetPort proposals;
    private final AiAssistantWriteToolService writes;
    private final AiAgentActionMapper actions;
    private final AiAgentRunMapper runs;
    private final AiChatMapper chat;
    private final WorkspaceService workspaces;
    private final AiRestrictionEpoch restriction;
    private final AiAgentRealtimePublisher realtime;
    private final AiAgentCapabilityGate gate;
    private final Clock clock;
    private final ObjectMapper json;
    private final ObjectProvider<AiAgentProposalDecisionService> self;
    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess;

    /** Every immutable selection is validated before the first independently committed effect. */
    @RequirePermission(Permission.AI_USE)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiAgentProposalDecisionResponse decide(int id, AiAgentProposalDecisionRequest request) {
        return recordVisibilityScopeControlAccess.withDeferredWorkspaceScope(workspaces.getCurrentWorkspaceId(),
                () -> decideWithinVisibilityScope(id, request));
    }

    private AiAgentProposalDecisionResponse decideWithinVisibilityScope(int id, AiAgentProposalDecisionRequest request) {
        reads.validateBatch(id, request);
        var results = new ArrayList<AiAgentProposalDecisionResponse.Item>();
        String stopped = null;
        for (var selected : request.items()) {
            if (stopped != null) {
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), "not_processed", stopped));
                continue;
            }
            try {
                PreliminaryPrincipals principals = discoverPrincipals(id, selected);
                String outcome = self.getObject().decideOne(id, request.expectedProposalRevision(), selected, principals);
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), outcome, null));
                publishInvalidation(id);
            } catch (TenantScopeSnapshotException invariant) {
                throw invariant;
            } catch (ForbiddenException | ResourceNotFoundException | AiAgentGoneException exception) {
                stopped = "access_lost";
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), "refused", stopped));
            } catch (AiAssistantProposalExpiredException exception) {
                stopped = "mission_review_expired";
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), "refused", stopped));
            } catch (ConflictException | AiAssistantLoopException exception) {
                stopped = "proposal_conflict";
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), "refused", stopped));
            } catch (RuntimeException failure) {
                stopped = "infrastructure_failure";
                String reconciled;
                try {
                    var canonical = reads.item(id, selected.actionSlot()).card();
                    reconciled = canonicalOutcome(canonical.status(), canonical.reviewState());
                } catch (TenantScopeSnapshotException invariant) {
                    throw invariant;
                } catch (RuntimeException unavailable) {
                    reconciled = "indeterminate";
                }
                results.add(new AiAgentProposalDecisionResponse.Item(selected.actionSlot(), reconciled, stopped));
            }
        }
        try {
            String version = reads.currentVersion(id);
            publishInvalidation(id);
            return new AiAgentProposalDecisionResponse(stopped == null, stopped, List.copyOf(results),
                    version, version, false);
        } catch (TenantScopeSnapshotException invariant) {
            throw invariant;
        } catch (ForbiddenException | ResourceNotFoundException | AiAgentGoneException | AiAssistantLoopException withheld) {
            return new AiAgentProposalDecisionResponse(false, "content_withheld", List.of(), null, null, true);
        } catch (RuntimeException unavailable) {
            return new AiAgentProposalDecisionResponse(false, "content_withheld", List.of(), null, null, true);
        }
    }

    private void publishInvalidation(int id) {
        try {
            AiAgentRun current = runs.getRun(workspaces.getCurrentWorkspaceId(), id);
            if (current != null) {
                realtime.publish(current, "mission_output");
            }
        } catch (RuntimeException unavailable) {
            return;
        }
    }

    /** Discovers mission ancestry before an inverse transaction can acquire any legacy root. */
    @RequirePermission(Permission.AI_USE)
    public ooo.klae.connex.backend.dto.AiAssistantToolCallDto undo(int sessionId, int toolCallId) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        AiChatToolCall call = chat.getMissionToolAncestry(workspaceId, sessionId, toolCallId, privacyGuard.currentGeneration(workspaceId));
        if (call == null || !"mission".equals(call.getProposalOrigin()) || call.getMissionRunId() == null
                || call.getMissionActionId() == null) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        return self.getObject().undoOne(sessionId, call.getMissionRunId(), call.getMissionActionId(), toolCallId);
    }

    /** Current mission CONFIRM tools have no inverse, but refusal retains mission-owned lock ordering. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public ooo.klae.connex.backend.dto.AiAssistantToolCallDto undoOne(int sessionId, int id, int actionId, int toolCallId) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        workspaces.lockAndRequirePermissionsSnapshot(workspaceId, Map.of(owner, Set.of(Permission.AI_USE)));
        var session = chat.getSessionByIdForUpdate(workspaceId, owner, sessionId);
        AiAgentRun run = runs.lockRun(workspaceId, id);
        var action = actions.lockAction(workspaceId, id, actionId);
        var call = chat.lockMissionToolCall(workspaceId, id, actionId);
        if (session == null || run == null || action == null || call == null || call.getId() != toolCallId
                || !Objects.equals(run.getOwnerUserId(), owner) || !Objects.equals(run.getSessionId(), sessionId)
                || !Objects.equals(call.getMissionRunId(), id) || !Objects.equals(call.getMissionActionId(), actionId)
                || !"mission".equals(call.getProposalOrigin()) || !AiAgentMissionCatalog.proposals(run)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        if (run.getWithheldAt() != null || run.getPurgedAt() != null) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        privacyGuard.requireCurrent(chat, new AiChatPrivacyToken(workspaceId, sessionId,
                run.getPrivacyGeneration(), session.getMissionTaintSeq(), privacyGuard.organizationId(workspaceId),
                run.getOrganizationPrivacyGeneration()));
        throw new ConflictException("Assistant tool cannot be undone");
    }

    private PreliminaryPrincipals discoverPrincipals(int id, AiAgentProposalDecisionRequest.Item selected) {
        if (!"approve".equals(selected.decision())) {
            return new PreliminaryPrincipals(List.of());
        }
        int workspaceId = workspaces.getCurrentWorkspaceId();
        var action = actions.getBySlot(workspaceId, id, selected.actionSlot());
        var call = action == null ? null : chat.getMissionToolCall(workspaceId, id, action.getId(), privacyGuard.currentGeneration(workspaceId));
        if (call == null) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        return "proposed".equals(call.getStatus()) ? writes.missionPrincipals(call) : new PreliminaryPrincipals(List.of());
    }

    /** One fresh transaction takes authority before mission roots and commits exactly one result. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public String decideOne(int id, String revision, AiAgentProposalDecisionRequest.Item selected,
            PreliminaryPrincipals principals) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        var required = new LinkedHashMap<Integer, Set<Permission>>();
        required.put(owner, Set.of(Permission.AI_USE));
        principals.userIds().forEach(principal -> required.putIfAbsent(principal, Set.of()));
        if ("approve".equals(selected.decision())) {
            recordVisibilityScopeControlAccess.getForWorkspace(workspaceId);
        }
        var authority = workspaces.lockAndRequirePermissionsSnapshot(workspaceId, required);
        AiAgentRun discovered = runs.getRun(workspaceId, id);
        if (discovered == null || discovered.getSessionId() == null || !Objects.equals(discovered.getOwnerUserId(), owner)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        var session = chat.getSessionByIdForUpdate(workspaceId, owner, discovered.getSessionId());
        AiAgentRun run = runs.lockRun(workspaceId, id);
        if (session == null || run == null || !Objects.equals(run.getOwnerUserId(), owner)
                || !Objects.equals(run.getSessionId(), session.getId()) || session.getCreatedByUserId() == null
                || !workspaces.isMember(workspaceId, session.getCreatedByUserId())
                || !"active".equals(session.getStatus())
                || (!Objects.equals(session.getCreatedByUserId(), owner)
                    && (!"shared".equals(session.getVisibility()) || !chat.isParticipant(workspaceId, session.getId(), owner)))) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        var discoveredAction = actions.getBySlot(workspaceId, id, selected.actionSlot());
        var action = discoveredAction == null ? null : actions.lockAction(workspaceId, id, discoveredAction.getId());
        var call = action == null ? null : chat.lockMissionToolCall(workspaceId, id, action.getId());
        if (action == null || call == null || !Integer.valueOf(action.getId()).equals(call.getMissionActionId())
                || !Integer.valueOf(id).equals(call.getMissionRunId()) || !"mission".equals(call.getProposalOrigin())
                || call.getOrganizationPrivacyGeneration() != run.getOrganizationPrivacyGeneration()) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        if (!AiAgentMissionCatalog.proposals(run) || run.getProposalFrozenAt() == null
                || run.getProposalMembershipRevision() != AiAgentContinuationCodec.decimal(revision)
                || !selected.expectedPreparationId().equals(action.getPreparationId()) || action.getOutputOrdinal() == null
                || action.getPrivacyGeneration() != run.getPrivacyGeneration()) {
            throw new ConflictException("mission_preparation_conflict");
        }
        if (run.getWithheldAt() != null || run.getPurgedAt() != null) {
            throw new AiAgentGoneException("mission_output_withheld");
        }
        var token = new AiChatPrivacyToken(workspaceId, session.getId(), run.getPrivacyGeneration(), session.getMissionTaintSeq(),
                privacyGuard.organizationId(workspaceId), run.getOrganizationPrivacyGeneration());
        privacyGuard.requireCurrent(chat, token);
        long epoch = restriction.current(workspaceId);
        if (!"proposed".equals(call.getStatus())) {
            retain(token, epoch);
            return outcome(call);
        }
        if (!"waiting_approval".equals(run.getStatus()) || run.getReviewExpiresAt() == null
                || !clock.instant().isBefore(run.getReviewExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw new ConflictException("mission_review_expired");
        }
        if ("approve".equals(selected.decision())) {
            gate.requireReady();
        }
        var scope = json.readValue(run.getScopeJson(), ooo.klae.connex.backend.dto.AiAgentRunCreateRequest.Scope.class);
        new AiAgentScopePolicy(scope).requireRecord(action.getTargetKind(), action.getTargetId());
        var envelope = json.readTree(call.getArgumentsJson());
        var target = envelope.path("target");
        if (!action.getToolName().equals(call.getToolName()) || !action.getToolName().equals(envelope.path("tool").asString())
                || !AiAgentMissionCatalog.confirmTools(scope.recordKind()).contains(action.getToolName())
                || !action.getTargetKind().equals(target.path("kind").asString())
                || action.getTargetId() != target.path("id").asInt()) {
            throw new ConflictException("mission_proposal_conflict");
        }
        proposals.decideLocked(new LockedDecisionContext(call, authority, token, epoch,
                action.getPreparedAt().toString(), run.getReviewExpiresAt().toInstant(ZoneOffset.UTC), principals), selected.decision());
        long version = run.getStateVersion();
        run.setStateVersion(Math.incrementExact(version));
        boolean pending = proposals.readFrozen(run).stream().map(member -> chat.getMissionToolCall(workspaceId, id, member.getId(), run.getOrganizationPrivacyGeneration()))
                .anyMatch(member -> member != null && "proposed".equals(member.getStatus()));
        if (!pending && "waiting_approval".equals(run.getStatus())) {
            run.setStatus("done");
            run.setFinishedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        }
        if (runs.updateRun(run, version, run.getExecutionGeneration()) != 1) {
            throw new ConflictException("mission_version_conflict");
        }
        return outcome(call);
    }

    private void retain(AiChatPrivacyToken token, long epoch) {
        if (!restriction.retainReadFenceUntilTransactionCompletionIfCurrent(token.workspaceId(), epoch)) {
            throw new ConflictException("mission_restrictions_changed");
        }
        privacyGuard.retain(chat, token);
    }

    private String outcome(AiChatToolCall call) {
        if (call.getResultJson() != null) {
            var result = json.readTree(call.getResultJson());
            if ("undone".equals(result.path("undo").path("status").asString())) {
                return "undone";
            }
            if ("mission_withdrawn".equals(result.path("reason").asString())) {
                return "withdrawn";
            }
        }
        return switch (call.getStatus()) {
            case "executed" -> "executed";
            case "rejected" -> "rejected";
            default -> "refused";
        };
    }

    private static String canonicalOutcome(String status, String reviewState) {
        if ("proposed".equals(status)) {
            return "refused";
        }
        return switch (reviewState) {
            case "applied" -> "executed";
            case "discarded" -> "rejected";
            case "withdrawn" -> "withdrawn";
            case "undone" -> "undone";
            default -> "indeterminate";
        };
    }
}
