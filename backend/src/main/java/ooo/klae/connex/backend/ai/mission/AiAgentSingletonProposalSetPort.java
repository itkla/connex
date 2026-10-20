package ooo.klae.connex.backend.ai.mission;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPreparedWrite;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCatalog.ToolTier;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolService.LockedDecisionContext;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.databind.ObjectMapper;

/** MANDATORY participants never enter reactive publication or dormant proposal-set storage. */
@Service
@RequiredArgsConstructor
public class AiAgentSingletonProposalSetPort implements AiAgentProposalSetPort {
    private final AiAgentActionMapper actions;
    private final AiAgentRunMapper runs;
    private final AiChatMapper chat;
    private final AiAssistantWriteToolService writes;
    private final ObjectMapper json;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public List<AiAgentAction> allocateSlots(AiAgentRun run, AiAgentScopePolicy scope) {
        if (!AiAgentMissionCatalog.proposals(run) || run.getProposalFrozenAt() != null) {
            throw new ConflictException("mission_state_conflict");
        }
        List<AiAgentAction> existing = lockInventory(run);
        for (String tool : AiAgentMissionCatalog.confirmTools(scope.kind()).stream().sorted().toList()) {
            String semantic = scope.kind() + ":" + scope.id() + ":" + tool;
            if (existing.stream().anyMatch(action -> semantic.equals(action.getSemanticSlotKey()))) {
                continue;
            }
            if (actions.countByRoot(run.getWorkspaceId(), run.getId()) >= 50) {
                throw new ConflictException("mission_action_limit");
            }
            var action = new AiAgentAction();
            action.setWorkspaceId(run.getWorkspaceId());
            action.setRootRunId(run.getId());
            action.setProducerRunId(run.getId());
            action.setActionSlot(UUID.randomUUID().toString());
            action.setSemanticSlotKey(semantic);
            action.setTargetKind(scope.kind());
            action.setTargetId(scope.id());
            action.setToolName(tool);
            action.setPlanItemKey("p0001");
            action.setPlanRevision(1);
            action.setPrivacyGeneration(run.getPrivacyGeneration());
            actions.insertSlot(action);
        }
        return actions.listByRoot(run.getWorkspaceId(), run.getId());
    }

    @Override
    public void validateInventory(AiAgentRun run, AiAgentContinuationCodec.Snapshot snapshot) {
        if (!AiAgentMissionCatalog.proposals(run) || snapshot == null) {
            throw new ConflictException("mission_action_inventory_conflict");
        }
        var inventory = actions.listByRoot(run.getWorkspaceId(), run.getId());
        var references = snapshot.checkpoint().actionSlotReferences();
        var scope = json.readTree(run.getScopeJson());
        String kind = scope.path("recordKind").asString();
        int targetId = scope.path("recordId").asInt();
        if (inventory.size() > 50 || inventory.size() != references.size()) {
            throw new ConflictException("mission_action_inventory_conflict");
        }
        for (var action : inventory) {
            if (action.getPrivacyGeneration() != run.getPrivacyGeneration()
                    || action.getRootRunId() != run.getId() || action.getProducerRunId() != run.getId()
                    || !kind.equals(action.getTargetKind()) || targetId != action.getTargetId()
                    || !AiAgentMissionCatalog.confirmTools(kind).contains(action.getToolName())
                    || !(kind + ":" + targetId + ":" + action.getToolName()).equals(action.getSemanticSlotKey())
                    || references.stream().noneMatch(reference -> reference.slotKey().equals(action.getActionSlot())
                        && reference.planItemKey().equals(action.getPlanItemKey()))) {
                throw new ConflictException("mission_action_inventory_conflict");
            }
        }
    }

    @Override
    public boolean hasInventory(AiAgentRun run) {
        return actions.countByRoot(run.getWorkspaceId(), run.getId()) > 0;
    }

    @Override
    public AiAgentAction findSlot(int workspaceId, int runId, String semanticKey) {
        return actions.getBySemantic(workspaceId, runId, semanticKey);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public AiChatToolCall publishPrepared(AiAgentRun run, AiAgentExecutionPolicy policy,
            AiAgentAction candidate, AiAssistantPreparedWrite write) {
        AiAgentAction action = actions.lockAction(run.getWorkspaceId(), run.getId(), candidate.getId());
        if (action == null || run.getProposalFrozenAt() != null || !AiAgentMissionCatalog.proposals(run)
                || write.tier() != ToolTier.CONFIRM || action.getPrivacyGeneration() != run.getPrivacyGeneration()
                || !action.getToolName().equals(write.toolName())
                || !action.getTargetKind().equals(write.targetKind()) || action.getTargetId() != write.targetId()) {
            throw new ConflictException("mission_action_conflict");
        }
        policy.scope().requireRecord(write.targetKind(), write.targetId());
        if (action.getPreparationId() != null) {
            if (!Arrays.equals(action.getIntentFingerprint(), candidate.getIntentFingerprint())) {
                throw new ConflictException("action_intent_conflict");
            }
            AiChatToolCall existing = chat.lockMissionToolCall(run.getWorkspaceId(), run.getId(), action.getId());
            if (existing == null) {
                throw new ConflictException("action_intent_conflict");
            }
            return existing;
        }
        if (candidate.getPreparedAt() == null || candidate.getSourceVersionsJson() == null
                || !Integer.valueOf(policy.segmentId()).equals(candidate.getOriginSegmentId())
                || !Long.valueOf(policy.executionGeneration()).equals(candidate.getOriginExecutionGeneration())) {
            throw new ConflictException("mission_action_conflict");
        }
        if (actions.bindPreparation(candidate) != 1) {
            throw new ConflictException("mission_action_conflict");
        }
        var call = new AiChatToolCall();
        call.setWorkspaceId(run.getWorkspaceId());
        call.setMessageId(policy.turn().userMessageId());
        call.setRequestedByUserId(policy.turn().userId());
        call.setToolName(write.toolName());
        call.setStatus("proposed");
        call.setArgumentsJson(write.argumentsJson());
        call.setServerProposal(true);
        call.setArtifactOrigin("mission");
        call.setProposalOrigin("mission");
        call.setMissionRunId(run.getId());
        call.setMissionActionId(action.getId());
        call.setPrivacyGeneration(run.getPrivacyGeneration());
        call.setIdempotencyKey("mission-action-" + action.getActionSlot());
        chat.insertMissionToolCall(call);
        long version = run.getStateVersion();
        run.setProposalMembershipRevision(Math.incrementExact(run.getProposalMembershipRevision()));
        run.setStateVersion(Math.incrementExact(version));
        if (runs.updateRun(run, version, run.getExecutionGeneration()) != 1) {
            throw new ConflictException("mission_version_conflict");
        }
        return call;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public List<AiAgentAction> lockInventory(AiAgentRun run) {
        List<AiAgentAction> inventory = actions.listByRoot(run.getWorkspaceId(), run.getId()).stream()
                .sorted(Comparator.comparingInt(AiAgentAction::getId)).toList();
        if (inventory.size() > 50) {
            throw new ConflictException("mission_action_limit");
        }
        var locked = new ArrayList<AiAgentAction>();
        for (AiAgentAction action : inventory) {
            AiAgentAction current = actions.lockAction(run.getWorkspaceId(), run.getId(), action.getId());
            if (current == null) {
                throw new ConflictException("mission_action_conflict");
            }
            locked.add(current);
        }
        var canonical = new ArrayList<AiChatToolCall>();
        for (AiAgentAction action : locked) {
            if (action.getPreparationId() != null) {
                AiChatToolCall call = chat.getMissionToolCall(run.getWorkspaceId(), run.getId(), action.getId());
                if (call == null || !"mission".equals(call.getProposalOrigin())
                        || !Objects.equals(call.getMissionRunId(), run.getId())
                        || !Objects.equals(call.getMissionActionId(), action.getId())) {
                    throw new ConflictException("mission_action_conflict");
                }
                canonical.add(call);
            }
        }
        canonical.stream().sorted(Comparator.comparingInt(AiChatToolCall::getId)).forEach(call -> {
            if (chat.lockMissionToolCall(run.getWorkspaceId(), run.getId(), call.getMissionActionId()) == null) {
                throw new ConflictException("mission_action_conflict");
            }
        });
        return locked;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public void freeze(AiAgentRun run, List<AiAgentAction> inventory, Instant frozenAt) {
        if (!AiAgentMissionCatalog.proposals(run) || run.getProposalFrozenAt() != null) {
            throw new ConflictException("mission_state_conflict");
        }
        List<AiAgentAction> bound = inventory.stream().filter(action -> action.getPreparationId() != null)
                .sorted(Comparator.comparingInt(AiAgentAction::getId)).toList();
        var expected = actions.listByRoot(run.getWorkspaceId(), run.getId()).stream()
                .filter(action -> action.getPreparationId() != null).map(AiAgentAction::getId).sorted().toList();
        if (!expected.equals(bound.stream().map(AiAgentAction::getId).sorted().toList())) {
            throw new ConflictException("mission_membership_conflict");
        }
        LocalDateTime earliest = null;
        int ordinal = 0;
        for (AiAgentAction action : bound) {
            AiChatToolCall call = chat.getMissionToolCall(run.getWorkspaceId(), run.getId(), action.getId());
            validateCanonical(run, action, call);
            if (call == null || !"mission".equals(call.getProposalOrigin()) || !call.isServerProposal()
                    || !"proposed".equals(call.getStatus()) || action.getPreparedAt() == null
                    || action.getPrivacyGeneration() != run.getPrivacyGeneration()) {
                throw new ConflictException("mission_action_conflict");
            }
            if (actions.assignOutputOrdinal(run.getWorkspaceId(), run.getId(), action.getId(), ++ordinal) != 1) {
                throw new ConflictException("mission_action_conflict");
            }
            earliest = earliest == null || action.getPreparedAt().isBefore(earliest) ? action.getPreparedAt() : earliest;
        }
        run.setProposalFrozenAt(LocalDateTime.ofInstant(frozenAt, ZoneOffset.UTC));
        run.setProposalMembershipRevision(Math.incrementExact(run.getProposalMembershipRevision()));
        run.setProposalFirstPreparedAt(earliest);
        LocalDateTime deadline = earliest == null ? null : earliest.plusHours(72);
        run.setReviewExpiresAt(deadline == null || deadline.isBefore(run.getHardDeadlineAt()) ? deadline : run.getHardDeadlineAt());
    }

    @Override
    public List<AiAgentAction> readFrozen(AiAgentRun run) {
        List<AiAgentAction> inventory = actions.listByRoot(run.getWorkspaceId(), run.getId());
        if (inventory.size() > 50 || run.getProposalFrozenAt() == null) {
            throw new ConflictException("mission_membership_conflict");
        }
        var bound = new ArrayList<AiAgentAction>();
        for (AiAgentAction action : inventory) {
            if (action.getPreparationId() == null) {
                if (action.getOutputOrdinal() != null) {
                    throw new ConflictException("mission_membership_conflict");
                }
                continue;
            }
            if (action.getOutputOrdinal() == null) {
                throw new ConflictException("mission_membership_conflict");
            }
            validateCanonical(run, action, chat.getMissionToolCall(run.getWorkspaceId(), run.getId(), action.getId()));
            bound.add(action);
        }
        bound.sort(Comparator.comparingInt(AiAgentAction::getOutputOrdinal));
        for (int index = 0; index < bound.size(); index++) {
            if (bound.get(index).getOutputOrdinal() != index + 1) {
                throw new ConflictException("mission_membership_conflict");
            }
        }
        return List.copyOf(bound);
    }

    private void validateCanonical(AiAgentRun run, AiAgentAction action, AiChatToolCall call) {
        if (call == null || !"mission".equals(call.getProposalOrigin()) || !"mission".equals(call.getArtifactOrigin())
                || !call.isServerProposal() || !Objects.equals(call.getMissionRunId(), run.getId())
                || !Objects.equals(call.getMissionActionId(), action.getId())
                || !Objects.equals(call.getToolName(), action.getToolName())
                || !Objects.equals(call.getPrivacyGeneration(), run.getPrivacyGeneration())
                || action.getPrivacyGeneration() != run.getPrivacyGeneration()
                || action.getPreparedAt() == null || action.getSourceObservedAt() == null
                || action.getOriginSegmentId() == null || action.getOriginExecutionGeneration() == null) {
            throw new ConflictException("mission_action_conflict");
        }
        var sources = new AiAgentSourceObservationsCodec().decode(action.getSourceVersionsJson());
        sources.requireComplete(action.getTargetKind(), action.getTargetId(), action.getPreparedAt().toInstant(ZoneOffset.UTC));
        if (!sources.firstObservedAt().equals(action.getSourceObservedAt().toInstant(ZoneOffset.UTC))) {
            throw new ConflictException("mission_action_conflict");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public void decideLocked(LockedDecisionContext context, String decision) {
        if ("approve".equals(decision)) {
            writes.approveLocked(context);
        } else if ("discard".equals(decision)) {
            writes.discardLocked(context);
        } else {
            throw new ConflictException("mission_decision_refused");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public void withdrawPending(AiAgentRun run, String reason) {
        chat.withdrawPendingMissionCalls(run.getWorkspaceId(), run.getId(), reason,
                json.writeValueAsString(Map.of("reason", "mission_withdrawn", "withdrawalReason", reason)));
    }
}
