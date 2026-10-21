package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.mission.AiAgentBudgetService.AiAgentDispatchReservation;
import ooo.klae.connex.backend.ai.mission.AiAgentContinuationCodec.Snapshot;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.ai.assistant.AiChatQueryScope;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiAgentRunCreateRequest;
import ooo.klae.connex.backend.dto.AiChatPageContextDto;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.AiAgentUnavailableException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import tools.jackson.databind.ObjectMapper;

/** Ordered mission transactions; every lease acquisition and release is the final database operation. */
@Service
@RequiredArgsConstructor
public class AiAgentRunPersistenceService {
    private final AiAgentRunMapper runs;
    private final AiChatPrivacyGuard privacyGuard;
    private final AiChatMapper chat;
    private final WorkspaceService workspaces;
    private final AiRestrictionEpoch restriction;
    private final AiRunLeaseService leases;
    private final ObjectMapper json;
    private final Clock clock;
    private final ObjectProvider<AiAgentRunPersistenceService> transactionalSelf;
    private final TenantWorkScope tenantWorkScope;
    private final PlatformTransactionManager transactionManager;
    private final AiAgentContinuationCodec codec = new AiAgentContinuationCodec();
    private final AiAgentBudgetService budget = new AiAgentBudgetService();
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentProposalSetPort proposals;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentProposalService proposalService;

    /** Creates the request, goal and first immutable taint boundary in one session transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAgentRun create(int sessionId, AiAgentRunCreateRequest request, String locale,
            byte[] requestHash, String scopeJson, long epoch) {
        int workspaceId = workspaces.getCurrentWorkspaceId();
        int owner = workspaces.getCurrentUserId();
        authorize(workspaceId, owner);
        AiChatSession session = lockSession(workspaceId, owner, sessionId);
        AiAgentRun existing = runs.findByRequestKey(workspaceId, owner, request.clientRequestKey());
        if (existing != null) {
            if (existing.getPurgedAt() != null) {
                throw new AiAgentGoneException("mission_purged");
            }
            privacyGuard.retain(chat, new AiChatPrivacyToken(workspaceId, sessionId,
                    existing.getPrivacyGeneration(), session.getMissionTaintSeq(),
                    privacyGuard.organizationId(workspaceId), existing.getOrganizationPrivacyGeneration()));
            if (!Arrays.equals(existing.getRequestHash(), requestHash)) {
                throw new ConflictException("mission_request_conflict");
            }
            return existing;
        }
        if (session.getMissionTaintSeq() == null && chat.countActiveTurns(workspaceId, sessionId) != 0) {
            throw new ConflictException("mission_session_busy");
        }
        var barrier = chat.getPrivacyBarrier(workspaceId);
        if (barrier == null || barrier.isBlocked()) {
            throw new AiAgentUnavailableException();
        }
        AiChatPrivacyToken captured = privacyGuard.capture(chat, session);
        int sequence = chat.nextMessageSequence(workspaceId, sessionId);
        if (session.getMissionTaintSeq() == null) {
            if (chat.taintSession(workspaceId, sessionId, captured.privacyGeneration(), sequence) != 1) {
                throw new ConflictException("mission_session_busy");
            }
            session.setMissionTaintSeq(sequence);
            session.setMissionTaintGeneration(captured.privacyGeneration());
        }
        AiChatPrivacyToken token = new AiChatPrivacyToken(workspaceId, sessionId,
                captured.privacyGeneration(), session.getMissionTaintSeq(), privacyGuard.organizationId(workspaceId), captured.organizationPrivacyGeneration());
        AiAgentRun run = new AiAgentRun();
        run.setWorkspaceId(workspaceId);
        run.setSessionId(sessionId);
        run.setOwnerUserId(owner);
        run.setClientRequestKey(request.clientRequestKey());
        run.setRequestHash(requestHash);
        run.setMissionType(request.missionType());
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion("record_preparation_v1".equals(request.missionType()) ? 3 : 2);
        run.setLocale(locale);
        run.setGoal(request.goal());
        run.setScopeJson(scopeJson);
        run.setScopeFingerprint(AiAgentCapabilityGate.hash(scopeJson));
        run.setStatus("queued");
        run.setStateVersion(1);
        run.setPrivacyGeneration(token.privacyGeneration());
        run.setOrganizationPrivacyGeneration(token.organizationPrivacyGeneration());
        run.setMaxSteps(request.limits().maxSteps());
        run.setMaxTokens(request.limits().maxTokens());
        run.setNextAttemptAt(now());
        run.setExecutionDeadlineAt(now().plusSeconds(request.limits().executionLifetimeSeconds()));
        run.setHardDeadlineAt(AiAgentMissionCatalog.proposals(run)
                ? run.getExecutionDeadlineAt().plusHours(72) : run.getExecutionDeadlineAt());
        runs.insertRun(run);
        codec.initialize(run);
        if (runs.updateContinuation(run, run.getStateVersion(), run.getExecutionGeneration(), null, 0, 0) != 1) {
            throw new ConflictException("mission_version_conflict");
        }
        AiChatMessage goal = message(run, sequence, "user", "goal", request.goal(), null);
        goal.setAuthorUserId(owner);
        chat.insertMessage(goal);
        chat.updateLastMessageAt(workspaceId, sessionId);
        fence(token, epoch);
        return run;
    }

    /** Reserves one remaining allowance and installs a real turn under the AGENT_RUN lease. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAgentExecutionPolicy claim(AiAgentRun discovered, byte[] declaration,
            AiRunLeaseGuard guard, AiAgentCapabilityGate gate) {
        int workspaceId = discovered.getWorkspaceId();
        Integer owner = discovered.getOwnerUserId();
        Integer sessionId = discovered.getSessionId();
        if (owner == null || sessionId == null) {
            throw new ConflictException("mission_purged");
        }
        authorize(workspaceId, owner);
        AiChatSession session = lockSession(workspaceId, owner, sessionId);
        AiAgentRun run = requireRun(workspaceId, discovered.getId(), sessionId);
        if (!"queued".equals(run.getStatus()) || (run.getNextAttemptAt() != null && run.getNextAttemptAt().isAfter(now()))) {
            throw new ConflictException("mission_state_conflict");
        }
        requireAllowance(run);
        if (chat.countActiveTurns(workspaceId, sessionId) != 0) {
            throw new ConflictException("mission_session_busy");
        }
        AiChatPrivacyToken token = new AiChatPrivacyToken(workspaceId, sessionId,
                run.getPrivacyGeneration(), session.getMissionTaintSeq(), privacyGuard.organizationId(workspaceId), run.getOrganizationPrivacyGeneration());
        privacyGuard.requireCurrent(chat, token);
        Snapshot snapshot = codec.decode(run);
        if (snapshot != null) {
            codec.validateExecutable(run, snapshot);
        }
        if (AiAgentMissionCatalog.proposals(run)) {
            proposals.validateInventory(run, snapshot);
            var scope = new AiAgentScopePolicy(json.readValue(run.getScopeJson(), AiAgentRunCreateRequest.Scope.class));
            var inventory = proposals.allocateSlots(run, scope);
            var before = snapshot.checkpoint();
            var checkpoint = new AiAgentCheckpoint(before.schemaVersion(), before.definitionVersion(), before.workspaceId(),
                    before.runId(), before.scopeFingerprint(), before.privacyGeneration(), before.revision(), before.planRevision(),
                    before.completedPlanItemKeys(), before.nextPlanItemKey(), before.lastCommittedSegmentNo(), before.completedFacts(),
                    inventory.stream().map(action -> new AiAgentCheckpoint.ActionSlotReference(action.getActionSlot(), action.getPlanItemKey())).toList(),
                    before.evidenceReferences(), before.coverage(), before.continuation(), before.sourceObservations());
            snapshot = new Snapshot(snapshot.plan(), checkpoint, snapshot.memory());
            codec.apply(run, snapshot);
            if (runs.updateContinuation(run, run.getStateVersion(), run.getExecutionGeneration(), null,
                    run.getPlanRevision(), run.getCheckpointRevision()) != 1) {
                throw new ConflictException("mission_revision_conflict");
            }
        }
        int logicalSegment = run.getRuntimeContractVersion() == 1 ? 1 : run.getLastCommittedSegmentNo() + 1;
        int attempts = attemptsForNextSegment(run);
        if (attempts >= 3 || logicalSegment > AiAgentMissionCatalog.maxSegments(run)) {
            throw new ConflictException("mission_attempt_limit");
        }
        long epoch = restriction.current(workspaceId);
        long previousGeneration = run.getExecutionGeneration();
        long previousVersion = run.getStateVersion();
        run.setExecutionGeneration(Math.incrementExact(previousGeneration));
        run.setStateVersion(Math.incrementExact(previousVersion));
        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspaceId);
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(owner);
        turn.setStatus("running");
        turn.setPrivacyGeneration(token.privacyGeneration());
        turn.setOrganizationPrivacyGeneration(token.organizationPrivacyGeneration());
        turn.setPrivacyTaintSeq(token.taintSeqAtAdmission());
        chat.insertTurn(turn);
        AiAgentSegment segment = new AiAgentSegment();
        segment.setWorkspaceId(workspaceId);
        segment.setRunId(run.getId());
        segment.setTurnId(turn.getId());
        segment.setSegmentNo(logicalSegment);
        segment.setAttemptNo(attempts + 1);
        segment.setExecutionGeneration(run.getExecutionGeneration());
        segment.setPrivacyGeneration(run.getPrivacyGeneration());
        segment.setOrganizationPrivacyGeneration(run.getOrganizationPrivacyGeneration());
        segment.setStatus("running");
        var reservation = budget.reserve(run, logicalSegment, clock.instant());
        segment.setReservedSteps(reservation.steps());
        segment.setReservedTokens(reservation.tokens());
        segment.setProviderDeclarationFingerprint(declaration);
        segment.setDeadlineAt(LocalDateTime.ofInstant(reservation.deadline(), ZoneOffset.UTC));
        segment.setStartedAt(now());
        runs.insertSegment(segment);
        chat.linkMissionTurn(workspaceId, sessionId, turn.getId(), run.getId(), segment.getId());
        run.setActiveSegmentId(segment.getId());
        run.setStatus("running");
        run.setReasonCode(null);
        run.setStartedAt(run.getStartedAt() == null ? now() : run.getStartedAt());
        run.setReservedSteps(segment.getReservedSteps());
        run.setReservedTokens(segment.getReservedTokens());
        update(run, previousVersion, previousGeneration);
        AiChatMessage goal = AiAgentMissionCatalog.continuation(run.getRuntimeContractVersion())
                ? runs.goalMessageIdentity(workspaceId, run.getId())
                : chat.getMissionMessage(workspaceId, run.getId(), "goal");
        if (goal == null) {
            throw new IllegalStateException("Mission goal unavailable");
        }
        goal.setContent(run.getGoal());
        AiAgentRunCreateRequest.Scope scope = json.readValue(run.getScopeJson(), AiAgentRunCreateRequest.Scope.class);
        AiChatQueuedTurn queued = new AiChatQueuedTurn(workspaceId, owner, sessionId, turn.getId(),
                goal.getId(), goal.getSeq(), epoch, false,
                List.of(new AiChatPageContextDto(scope.recordKind(), scope.recordId())), List.of(),
                AiPrivacyMode.MASKED, false, AiChatQueryScope.none(), token);
        fence(token, epoch);
        var lease = leases.acquireInCurrentTransaction(new AiRunLeaseKey(workspaceId,
                AiRunLeaseSubject.AGENT_RUN, run.getId()), guard);
        return new AiAgentExecutionPolicy(run.getId(), segment.getId(), run.getExecutionGeneration(),
                queued, goal, new AiAgentScopePolicy(scope), segment.getReservedSteps(),
                segment.getDeadlineAt().toInstant(ZoneOffset.UTC), lease, guard, transactionalSelf.getObject(), gate,
                run.getRuntimeContractVersion(), logicalSegment, segment.getAttemptNo(), snapshot,
                reservation.continuationCapable(), segment.getReservedTokens());
    }

    /** Current-state nonlocking execution predicate, safe inside dispatch/publication fences. */
    public void requireRunning(AiAgentExecutionPolicy policy) {
        AiChatQueuedTurn turn = policy.turn();
        privacyGuard.requireCurrent(chat, turn.privacyToken());
        AiAgentRun run = runs.getRun(turn.workspaceId(), policy.runId());
        requireIdentity(run, policy);
        if (!clock.instant().isBefore(policy.deadline())) {
            throw new AiAssistantLoopException("mission_expired", "mission_expired");
        }
        AiChatSession session = chat.getAccessibleSessionById(turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (!workspaces.isMember(turn.workspaceId(), turn.userId()) || session == null
                || session.getCreatedByUserId() == null
                || !workspaces.isMember(turn.workspaceId(), session.getCreatedByUserId())) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        workspaces.requirePermission(turn.workspaceId(), turn.userId(), Permission.AI_USE);
    }

    /** Reads the durable allowance so repairs cannot spend the final report decision on a tool. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int remainingDispatches(AiAgentExecutionPolicy policy) {
        lockExecution(policy);
        AiAgentSegment segment = lockAttempt(policy);
        return segment.getReservedSteps() - segment.getKnownSteps();
    }

    /** Reads the active attempt's unused allowance before constructing a bounded provider request. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int maxOutputTokens(AiAgentExecutionPolicy policy, int promptTokens, int requestedOutputTokens) {
        lockExecution(policy);
        return budget.maxOutputTokens(lockAttempt(policy), promptTokens, requestedOutputTokens);
    }

    /** Records each attempted model call before its local dispatch fence, including fallback calls. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiAgentDispatchReservation reserveDispatch(AiAgentExecutionPolicy policy, long tokenCeiling) {
        lockExecution(policy);
        AiAgentSegment segment = lockAttempt(policy);
        long known = Math.addExact(segment.getKnownInputTokens(), segment.getKnownOutputTokens());
        if (segment.isUsageUncertain() || segment.getKnownSteps() >= segment.getReservedSteps()
                || tokenCeiling <= 0 || tokenCeiling > segment.getReservedTokens() - known
                || runs.reserveSegmentDispatch(policy.turn().workspaceId(), policy.runId(), policy.segmentId(),
                        policy.executionGeneration(), segment.getKnownSteps(), tokenCeiling) != 1) {
            throw new AiAssistantLoopException("mission_budget_exhausted", "mission_budget_exhausted");
        }
        int ordinal = Math.incrementExact(segment.getKnownSteps());
        segment.setKnownSteps(ordinal);
        segment.setUsageUncertain(true);
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        return new AiAgentDispatchReservation(policy.runId(), policy.segmentId(), policy.executionGeneration(),
                ordinal, tokenCeiling);
    }

    /** Settles only the exact outstanding provider dispatch; duplicates cannot charge its successor. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(AiAgentExecutionPolicy policy, AiAgentDispatchReservation dispatch, int input, int output) {
        AiAgentRun run = lockExecution(policy, false);
        AiAgentSegment segment = lockAttempt(policy);
        if (input < 0 || output < 0 || dispatch == null || dispatch.runId() != policy.runId()
                || dispatch.segmentId() != policy.segmentId() || dispatch.executionGeneration() != policy.executionGeneration()) {
            throw new IllegalArgumentException("Invalid provider usage");
        }
        if (!segment.isUsageUncertain() || segment.getKnownSteps() != dispatch.dispatchOrdinal()) {
            return;
        }
        if (runs.recordDispatchUsage(policy.turn().workspaceId(), policy.runId(), policy.segmentId(),
                policy.executionGeneration(), dispatch.dispatchOrdinal(), input, output) != 1) {
            throw new ConflictException("mission_dispatch_conflict");
        }
        segment.setKnownInputTokens(Math.addExact(segment.getKnownInputTokens(), input));
        segment.setKnownOutputTokens(Math.addExact(segment.getKnownOutputTokens(), output));
        segment.setUsageUncertain(false);
        boolean overrun = Math.addExact((long) input, output) > dispatch.tokenCeiling()
                || Math.addExact(segment.getKnownInputTokens(), segment.getKnownOutputTokens()) > segment.getReservedTokens();
        if (overrun) {
            settle(run, segment, "paused", "usage_overrun", true);
        }
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        if (overrun) {
            leases.releaseHeldInCurrentTransaction(policy.lease().key());
        }
    }

    /** Reads current durable continuation without loading any prior transcript content. */
    public Snapshot snapshot(AiAgentExecutionPolicy policy) {
        requireRunning(policy);
        AiAgentRun run = runs.getRun(policy.turn().workspaceId(), policy.runId());
        requireIdentity(run, policy);
        Snapshot snapshot = codec.decode(run);
        codec.validateExecutable(run, snapshot);
        if (policy.proposalCapable()) {
            proposals.validateInventory(run, snapshot);
        }
        return snapshot;
    }

    /** Returns an exact screened bookkeeping replay after locking current authorization and ancestry. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public String replayBookkeeping(AiAgentExecutionPolicy policy, int step, int ordinal, String arguments) {
        lockExecution(policy);
        AiChatToolCall call = runs.findMissionBookkeepingCall(policy.turn().workspaceId(), policy.runId(),
                bookkeepingKey(policy, step, ordinal));
        lockAttempt(policy);
        if (call == null) {
            return null;
        }
        requireReplay(call, arguments);
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        return call.getResultJson();
    }

    /** Commits a screened plan, immutable facts, revisions and its tool replay as one ordered write. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public String commitPlan(AiAgentExecutionPolicy policy, int step, int ordinal, String arguments,
            String result, Snapshot prepared) {
        return commitBookkeeping(policy, step, ordinal, "set_todos", arguments, result, prepared, false);
    }

    /** Atomically commits continuation and accounting, queues the successor and releases the lease last. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public String commitCheckpoint(AiAgentExecutionPolicy policy, int step, int ordinal, String arguments,
            String result, Snapshot prepared) {
        return commitBookkeeping(policy, step, ordinal, "checkpoint_segment", arguments, result, prepared, true);
    }

    private String commitBookkeeping(AiAgentExecutionPolicy policy, int step, int ordinal, String name,
            String arguments, String result, Snapshot prepared, boolean checkpoint) {
        if (!AiAgentMissionCatalog.continuation(policy.runtimeContractVersion()) || prepared == null || arguments == null || result == null
                || arguments.getBytes(StandardCharsets.UTF_8).length > 65536
                || result.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new AiAssistantLoopException("mission_content_refused", "mission_content_refused");
        }
        AiAgentRun run = lockExecution(policy);
        AiChatToolCall replay = runs.findMissionBookkeepingCall(policy.turn().workspaceId(), policy.runId(),
                bookkeepingKey(policy, step, ordinal));
        AiAgentSegment segment = lockAttempt(policy);
        if (replay != null) {
            requireReplay(replay, arguments);
            fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
            return replay.getResultJson();
        }
        Snapshot previous = codec.decode(run);
        long planRevision = run.getPlanRevision();
        long checkpointRevision = run.getCheckpointRevision();
        long version = run.getStateVersion();
        long generation = run.getExecutionGeneration();
        if (Long.parseLong(prepared.checkpoint().revision()) != Math.incrementExact(checkpointRevision)
                || Long.parseLong(prepared.plan().revision()) != (checkpoint ? planRevision : Math.incrementExact(planRevision))
                || prepared.checkpoint().lastCommittedSegmentNo() != run.getLastCommittedSegmentNo() + (checkpoint ? 1 : 0)
                || !prepared.checkpoint().actionSlotReferences().equals(previous.checkpoint().actionSlotReferences())
                || !prepared.checkpoint().completedFacts().containsAll(previous.checkpoint().completedFacts())
                || (checkpoint && !prepared.plan().equals(previous.plan()))) {
            throw new ConflictException("mission_revision_conflict");
        }
        if (checkpoint && (!policy.continuationCapable() || segment.isUsageUncertain()
                || policy.segmentNo() != run.getLastCommittedSegmentNo() + 1
                || run.getConsumedSteps() + segment.getKnownSteps() >= run.getMaxSteps()
                || run.getConsumedTokens() + segment.getKnownInputTokens() + segment.getKnownOutputTokens() >= run.getMaxTokens())) {
            throw new AiAssistantLoopException("mission_budget_exhausted", "mission_budget_exhausted");
        }
        codec.validateTransition(previous, prepared, checkpoint);
        codec.apply(run, prepared);
        codec.validateExecutable(run, prepared);
        run.setStateVersion(Math.incrementExact(version));
        if (runs.updateContinuation(run, version, generation, policy.segmentId(), planRevision, checkpointRevision) != 1) {
            throw new ConflictException("mission_revision_conflict");
        }
        AiChatToolCall call = new AiChatToolCall();
        call.setWorkspaceId(policy.turn().workspaceId());
        call.setMessageId(policy.turn().userMessageId());
        call.setMissionRunId(policy.runId());
        call.setArtifactOrigin("mission");
        call.setPrivacyGeneration(policy.turn().privacyToken().privacyGeneration());
        call.setOrganizationPrivacyGeneration(policy.turn().privacyToken().organizationPrivacyGeneration());
        call.setToolName(name);
        call.setStatus("executed");
        call.setArgumentsJson(arguments);
        call.setResultJson(result);
        call.setExecutedByUserId(policy.turn().userId());
        call.setIdempotencyKey(bookkeepingKey(policy, step, ordinal));
        if (runs.insertMissionBookkeepingCall(call) != 1) {
            throw new ConflictException("mission_bookkeeping_conflict");
        }
        if (checkpoint) {
            run.setNextAttemptAt(now());
            settle(run, segment, "queued", null, false);
        }
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        if (checkpoint) {
            leases.releaseHeldInCurrentTransaction(policy.lease().key());
        }
        return result;
    }

    private String bookkeepingKey(AiAgentExecutionPolicy policy, int step, int ordinal) {
        if (step < 0 || ordinal < 0) {
            throw new IllegalArgumentException("Invalid mission bookkeeping identity");
        }
        return "mission-" + policy.runId() + "-" + policy.segmentId() + "-" + step + "-" + ordinal;
    }

    private void requireReplay(AiChatToolCall call, String arguments) {
        if (call.getArgumentsJson() == null || arguments == null
                || !json.readTree(call.getArgumentsJson()).equals(json.readTree(arguments))
                || !"executed".equals(call.getStatus()) || call.getResultJson() == null) {
            throw new ConflictException("mission_bookkeeping_conflict");
        }
    }

    /** Persists only allowed read-call arguments; provider-native replay state remains transient. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(AiAgentExecutionPolicy policy, int step, int ordinal, String name, String arguments) {
        if (!AiAgentScopePolicy.TOOLS.contains(name)) {
            throw new AiAssistantLoopException("mission_tool_refused", "mission_tool_refused");
        }
        policy.scope().requireTool(name);
        lockExecution(policy);
        lockAttempt(policy);
        AiChatToolCall call = new AiChatToolCall();
        call.setWorkspaceId(policy.turn().workspaceId());
        call.setMessageId(policy.turn().userMessageId());
        call.setMissionRunId(policy.runId());
        call.setArtifactOrigin("mission");
        call.setPrivacyGeneration(policy.turn().privacyToken().privacyGeneration());
        call.setOrganizationPrivacyGeneration(policy.turn().privacyToken().organizationPrivacyGeneration());
        call.setToolName(name);
        call.setStatus("proposed");
        call.setArgumentsJson(arguments);
        call.setIdempotencyKey("mission-" + policy.runId() + "-" + policy.segmentId() + "-" + step + "-" + ordinal);
        chat.insertToolCall(call);
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        return call.getId();
    }

    /** Updates a read result only under its exact live mission attempt. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean finishTool(AiAgentExecutionPolicy policy, int id, String status, String result) {
        lockExecution(policy);
        AiChatToolCall call = chat.getToolCallBySessionForUpdate(policy.turn().workspaceId(),
                policy.turn().sessionId(), id);
        if (call == null || !Objects.equals(call.getMissionRunId(), policy.runId())
                || !Objects.equals(call.getMessageId(), policy.turn().userMessageId())
                || !"mission".equals(call.getArtifactOrigin())
                || !"proposed".equals(call.getStatus())
                || call.getIdempotencyKey() == null
                || !call.getIdempotencyKey().startsWith("mission-" + policy.runId() + "-" + policy.segmentId() + "-")) {
            throw new ConflictException("mission_state_conflict");
        }
        lockAttempt(policy);
        boolean changed = chat.updateToolCall(policy.turn().workspaceId(), policy.turn().userMessageId(),
                id, status, result, policy.turn().userId()) == 1;
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        return changed;
    }

    /** Prepares workspace scopes before atomically publishing an admitted singleton and its provenance. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiChatToolCall publishPrepared(AiAgentExecutionPolicy policy, AiAgentProposalService.Prepared prepared) {
        return proposalTransaction(policy, () -> publishPreparedInWorkspace(policy, prepared));
    }

    private AiChatToolCall publishPreparedInWorkspace(AiAgentExecutionPolicy policy, AiAgentProposalService.Prepared prepared) {
        AiAgentRun run = lockExecution(policy);
        proposals.lockInventory(run);
        lockAttempt(policy);
        proposalService.revalidate(policy, List.of(prepared.action()));
        AiChatToolCall call = proposals.publishPrepared(run, policy, prepared.action(), prepared.write());
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        return call;
    }

    /** Prepares workspace scopes before atomically publishing the report, accounting and lease release. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean resolve(AiAgentExecutionPolicy policy, String text, String metadata) {
        return proposalTransaction(policy, () -> resolveInWorkspace(policy, text, metadata));
    }

    private boolean resolveInWorkspace(AiAgentExecutionPolicy policy, String text, String metadata) {
        if (text == null || text.isBlank() || text.length() > 16000 || SpecialCareTextScreen.screen(text).excluded()) {
            throw new AiAssistantLoopException("malformed_output", "malformed_output");
        }
        AiAgentRun run = lockExecution(policy);
        var inventory = AiAgentMissionCatalog.proposals(run) ? proposals.lockInventory(run) : List.<ooo.klae.connex.backend.beans.AiAgentAction>of();
        AiAgentSegment segment = lockAttempt(policy);
        if (segment.isUsageUncertain() || run.getReportMessageId() != null) {
            throw new ConflictException("mission_state_conflict");
        }
        var tree = json.readTree(metadata);
        if (!(tree instanceof tools.jackson.databind.node.ObjectNode object)) {
            throw new IllegalStateException("Invalid mission report metadata");
        }
        object.put("schemaVersion", 1);
        object.set("coverage", json.valueToTree(Map.of("processed", 1, "skipped", 0, "failed", 0, "unprocessed", 0)));
        AiChatMessage report = message(run, chat.nextMessageSequence(run.getWorkspaceId(), policy.turn().sessionId()),
                "assistant", "report", text, json.writeValueAsString(object));
        chat.insertMessage(report);
        run.setReportMessageId(report.getId());
        if (AiAgentMissionCatalog.proposals(run)) {
            proposalService.revalidate(policy, inventory);
            proposals.freeze(run, inventory, clock.instant());
        }
        settle(run, segment, run.getReviewExpiresAt() == null ? "done" : "waiting_approval", null, false);
        chat.updateLastMessageAt(run.getWorkspaceId(), policy.turn().sessionId());
        fence(policy.turn().privacyToken(), policy.turn().restrictionEpoch());
        leases.releaseHeldInCurrentTransaction(policy.lease().key());
        return true;
    }

    private <T> T proposalTransaction(AiAgentExecutionPolicy policy, Supplier<T> operation) {
        return tenantWorkScope.inWorkspace(policy.turn().workspaceId(), () -> {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return transaction.execute(status -> operation.get());
        });
    }

    /** Owner controls retain original caps/deadlines and need no provider readiness to stop. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiAgentRun control(AiAgentRun discovered, long expectedVersion, String action) {
        if (discovered.getSessionId() == null || discovered.getOwnerUserId() == null) {
            throw new AiAgentGoneException("mission_purged");
        }
        authorize(discovered.getWorkspaceId(), discovered.getOwnerUserId());
        AiChatSession session = lockSession(discovered.getWorkspaceId(), discovered.getOwnerUserId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (run.getStateVersion() != expectedVersion) {
            throw new ConflictException("mission_version_conflict");
        }
        if (Set.of("done", "failed", "cancelled").contains(run.getStatus())) {
            if ("cancel".equals(action) && "cancelled".equals(run.getStatus())) {
                return run;
            }
            throw new ConflictException("mission_state_conflict");
        }
        if ("waiting_approval".equals(run.getStatus()) && !"cancel".equals(action)) {
            throw new ConflictException("mission_state_conflict");
        }
        if ("resume".equals(action)) {
            if (!"paused".equals(run.getStatus())) {
                throw new ConflictException("mission_state_conflict");
            }
            requireAllowance(run);
            if (attemptsForNextSegment(run) >= 3) {
                throw new ConflictException("mission_attempt_limit");
            }
            var token = new AiChatPrivacyToken(run.getWorkspaceId(), run.getSessionId(), run.getPrivacyGeneration(),
                    session.getMissionTaintSeq(), privacyGuard.organizationId(run.getWorkspaceId()), run.getOrganizationPrivacyGeneration());
            long version = run.getStateVersion();
            run.setStatus("queued");
            run.setReasonCode(null);
            run.setStateVersion(Math.incrementExact(version));
            run.setNextAttemptAt(now());
            update(run, version, run.getExecutionGeneration());
            fence(token, restriction.current(run.getWorkspaceId()));
            return run;
        }
        if ("pause".equals(action) && "paused".equals(run.getStatus()) && "member".equals(run.getReasonCode())) {
            return run;
        }
        String state = "pause".equals(action) ? "paused" : "cancelled";
        if (proposals.hasInventory(run)) {
            proposals.lockInventory(run);
            if ("cancelled".equals(state)) {
                proposals.withdrawPending(run, "cancelled");
            }
        }
        AiAgentSegment segment = activeSegment(run);
        settle(run, segment, state, "pause".equals(action) ? "member" : "cancelled", true);
        if (segment != null) {
            leases.releaseHeldInCurrentTransaction(new AiRunLeaseKey(run.getWorkspaceId(), AiRunLeaseSubject.AGENT_RUN, run.getId()));
        }
        return run;
    }

    /** Backs off preclaim readiness for interruption recovery without reclassifying the stop. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deferInterrupted(AiAgentRun discovered) {
        if (discovered.getSessionId() == null) {
            return;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (!"paused".equals(run.getStatus()) || !"interrupted".equals(run.getReasonCode())
                || run.getStateVersion() != discovered.getStateVersion()) {
            return;
        }
        long version = run.getStateVersion();
        run.setNextAttemptAt(now().plusSeconds(30));
        run.setStateVersion(Math.incrementExact(version));
        update(run, version, run.getExecutionGeneration());
    }

    /** Backs off a refused claim without consuming an attempt or refreshing its lifetime. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void defer(AiAgentRun discovered) {
        if (discovered.getSessionId() == null) {
            return;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (!"queued".equals(run.getStatus()) || run.getStateVersion() != discovered.getStateVersion()) {
            return;
        }
        long version = run.getStateVersion();
        run.setNextAttemptAt(now().plusSeconds(30));
        run.setReasonCode("unavailable");
        run.setStateVersion(Math.incrementExact(version));
        update(run, version, run.getExecutionGeneration());
    }

    /** Removes expired execution capability even when AI is disabled or the requester has departed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean expire(AiAgentRun discovered) {
        if (discovered.getSessionId() == null) {
            return false;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (Set.of("done", "failed", "cancelled").contains(run.getStatus())
                || ("waiting_approval".equals(run.getStatus())
                        ? run.getReviewExpiresAt() == null || now().isBefore(run.getReviewExpiresAt())
                        : now().isBefore(run.getExecutionDeadlineAt()))) {
            return false;
        }
        boolean review = "waiting_approval".equals(run.getStatus());
        if (proposals.hasInventory(run)) {
            proposals.lockInventory(run);
            proposals.withdrawPending(run, review ? "review_expired" : "execution_expired");
        }
        AiAgentSegment segment = activeSegment(run);
        settle(run, segment, "failed", review ? "review_expired" : "expired", true);
        if (segment != null) {
            leases.releaseHeldInCurrentTransaction(new AiRunLeaseKey(run.getWorkspaceId(), AiRunLeaseSubject.AGENT_RUN, run.getId()));
        }
        return true;
    }

    /** Revokes one departed requester's capability under maintenance authority and exact discovery identity. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelForDeparture(AiAgentRun discovered) {
        if (discovered.getSessionId() == null) {
            return;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (!Objects.equals(run.getOwnerUserId(), discovered.getOwnerUserId())
                || run.getExecutionGeneration() != discovered.getExecutionGeneration()
                || Set.of("done", "failed", "cancelled").contains(run.getStatus())) {
            return;
        }
        if (proposals.hasInventory(run)) {
            proposals.lockInventory(run);
            proposals.withdrawPending(run, "owner_departed");
        }
        AiAgentSegment segment = activeSegment(run);
        settle(run, segment, "cancelled", "owner_departed", true);
        if (segment != null) {
            leases.releaseHeldInCurrentTransaction(new AiRunLeaseKey(run.getWorkspaceId(), AiRunLeaseSubject.AGENT_RUN, run.getId()));
        }
    }

    /** Maintenance interruption never requires a departed owner's execution permission. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void interrupt(AiAgentRun discovered, String reason) {
        if (discovered.getSessionId() == null) {
            return;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (!"running".equals(run.getStatus()) || run.getExecutionGeneration() != discovered.getExecutionGeneration()) {
            return;
        }
        AiAgentSegment segment = activeSegment(run);
        settle(run, segment, AiAgentMissionCatalog.proposals(run)
                && !AiAgentMissionCatalog.automaticRetryReason(reason) ? "failed" : "paused", reason, true);
        leases.releaseHeldInCurrentTransaction(new AiRunLeaseKey(run.getWorkspaceId(), AiRunLeaseSubject.AGENT_RUN, run.getId()));
    }

    /** Atomically settles the expected orphan before taking over and retiring its lease last. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean settleOrphan(AiAgentRun discovered, AiRunLeaseKey key, long expectedEpoch) {
        if (discovered.getSessionId() == null) {
            return false;
        }
        chat.getSessionByIdForMaintenanceUpdate(discovered.getWorkspaceId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (!"running".equals(run.getStatus()) || run.getExecutionGeneration() != discovered.getExecutionGeneration()) {
            return false;
        }
        settle(run, activeSegment(run), "paused", "interrupted", true);
        if (leases.takeOverForSettlement(key, expectedEpoch).isEmpty()) {
            throw new ConflictException("mission_state_conflict");
        }
        leases.releaseHeldInCurrentTransaction(key);
        return true;
    }

    /** Queues only the exact genuinely interrupted READ identity after fresh preclaim gates. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean requeueInterrupted(AiAgentRun discovered) {
        if (discovered.getOwnerUserId() == null || discovered.getSessionId() == null) {
            return false;
        }
        authorize(discovered.getWorkspaceId(), discovered.getOwnerUserId());
        AiChatSession session = lockSession(discovered.getWorkspaceId(), discovered.getOwnerUserId(), discovered.getSessionId());
        AiAgentRun run = requireRun(discovered.getWorkspaceId(), discovered.getId(), discovered.getSessionId());
        if (run.getStateVersion() != discovered.getStateVersion()
                || run.getExecutionGeneration() != discovered.getExecutionGeneration()
                || !AiAgentMissionCatalog.continuation(run.getRuntimeContractVersion()) || !"paused".equals(run.getStatus())
                || !AiAgentMissionCatalog.automaticRetryReason(run.getReasonCode())
                || (run.getNextAttemptAt() != null && run.getNextAttemptAt().isAfter(now()))) {
            return false;
        }
        requireAllowance(run);
        if (attemptsForNextSegment(run) >= 3) {
            return false;
        }
        var token = new AiChatPrivacyToken(run.getWorkspaceId(), run.getSessionId(), run.getPrivacyGeneration(),
                session.getMissionTaintSeq(), privacyGuard.organizationId(run.getWorkspaceId()), run.getOrganizationPrivacyGeneration());
        long version = run.getStateVersion();
        run.setStatus("queued");
        run.setReasonCode(null);
        run.setStateVersion(Math.incrementExact(version));
        run.setNextAttemptAt(now());
        update(run, version, run.getExecutionGeneration());
        fence(token, restriction.current(run.getWorkspaceId()));
        return true;
    }

    /** Uses the current logical segment's maximum attempt ordinal, never aggregate row count. */
    public int attemptsForNextSegment(AiAgentRun run) {
        int logical = run.getRuntimeContractVersion() == 1 ? 1 : run.getLastCommittedSegmentNo() + 1;
        List<AiAgentSegment> segments = runs.listSegments(run.getWorkspaceId(), run.getId());
        if (segments.size() > (run.getRuntimeContractVersion() == 1 ? 3 : 24)) {
            throw new ConflictException("mission_attempt_limit");
        }
        return segments.stream().filter(segment -> segment.getSegmentNo() == logical)
                .mapToInt(AiAgentSegment::getAttemptNo).max().orElse(0);
    }

    private AiAgentRun lockExecution(AiAgentExecutionPolicy policy) {
        return lockExecution(policy, true);
    }

    private AiAgentRun lockExecution(AiAgentExecutionPolicy policy, boolean requireOpenDeadline) {
        var turn = policy.turn();
        authorize(turn.workspaceId(), turn.userId());
        lockSession(turn.workspaceId(), turn.userId(), turn.sessionId());
        AiAgentRun run = requireRun(turn.workspaceId(), policy.runId(), turn.sessionId());
        requireIdentity(run, policy);
        if (policy.proposalCapable()) {
            proposals.lockInventory(run);
            proposals.validateInventory(run, codec.decode(run));
        }
        if (requireOpenDeadline && !clock.instant().isBefore(policy.deadline())) {
            throw new AiAssistantLoopException("mission_expired", "mission_expired");
        }
        privacyGuard.requireCurrent(chat, turn.privacyToken());
        return run;
    }

    private AiAgentSegment lockAttempt(AiAgentExecutionPolicy policy) {
        var turn = policy.turn();
        AiChatTurn stored = chat.getTurnByIdForUpdate(turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (stored == null || !Objects.equals(stored.getMissionRunId(), policy.runId())
                || !Objects.equals(stored.getMissionSegmentId(), policy.segmentId()) || !"running".equals(stored.getStatus())) {
            throw new ConflictException("mission_state_conflict");
        }
        AiAgentSegment segment = runs.lockSegment(turn.workspaceId(), policy.segmentId());
        if (segment == null || segment.getExecutionGeneration() != policy.executionGeneration()
                || !"running".equals(segment.getStatus())) {
            throw new ConflictException("mission_state_conflict");
        }
        return segment;
    }

    private AiAgentSegment activeSegment(AiAgentRun run) {
        if (proposals.hasInventory(run)) {
            proposals.lockInventory(run);
        }
        if (run.getActiveSegmentId() == null) {
            return null;
        }
        AiAgentSegment candidate = runs.getSegment(run.getWorkspaceId(), run.getActiveSegmentId());
        if (candidate == null || candidate.getTurnId() == null || run.getSessionId() == null) {
            throw new IllegalStateException("Active mission attempt unavailable");
        }
        chat.getTurnByIdForUpdate(run.getWorkspaceId(), run.getSessionId(), candidate.getTurnId());
        return runs.lockSegment(run.getWorkspaceId(), candidate.getId());
    }

    private void settle(AiAgentRun run, AiAgentSegment segment, String state, String reason, boolean interrupted) {
        long version = run.getStateVersion();
        long generation = run.getExecutionGeneration();
        if (Set.of("failed", "cancelled").contains(state) && proposals.hasInventory(run)) {
            proposals.withdrawPending(run, "cancelled".equals(state) ? "cancelled" : "mission_failed");
        }
        if (segment != null) {
            var charge = budget.settle(segment);
            run.setConsumedSteps(Math.addExact(run.getConsumedSteps(), charge.steps()));
            run.setConsumedTokens(Math.addExact(run.getConsumedTokens(), charge.tokens()));
            segment.setStatus(interrupted ? "interrupted" : "completed");
            segment.setSettledAt(now());
            segment.setFailureReason(reason);
            if (segment.getTurnId() != null && run.getSessionId() != null) {
                chat.clearTurnContent(run.getWorkspaceId(), run.getSessionId(), segment.getTurnId());
                chat.updateTurnTerminal(run.getWorkspaceId(), run.getSessionId(), segment.getTurnId(),
                        interrupted ? "cancelled" : "resolved", reason, "running", null);
            }
            if (runs.updateSegment(segment, "running") != 1) {
                throw new ConflictException("mission_state_conflict");
            }
        }
        run.setActiveSegmentId(null);
        run.setReservedSteps(0);
        run.setReservedTokens(0);
        run.setStatus(state);
        run.setReasonCode(reason);
        run.setStateVersion(Math.incrementExact(version));
        run.setExecutionGeneration(Math.incrementExact(generation));
        if (Set.of("done", "failed", "cancelled").contains(state)) {
            run.setFinishedAt(now());
        }
        update(run, version, generation);
    }

    private void authorize(int workspaceId, int owner) {
        if (workspaces.getCurrentWorkspaceId() != workspaceId || workspaces.getCurrentUserId() != owner) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        workspaces.lockAndRequirePermissionsSnapshot(workspaceId, Map.of(owner, Set.of(Permission.AI_USE)));
        if (!workspaces.isMember(workspaceId, owner)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
    }

    private AiChatSession lockSession(int workspaceId, int owner, int sessionId) {
        AiChatSession session = chat.getSessionByIdForUpdate(workspaceId, owner, sessionId);
        if (session == null || session.getCreatedByUserId() == null
                || !workspaces.isMember(workspaceId, session.getCreatedByUserId())
                || !"active".equals(session.getStatus())
                || (!Objects.equals(session.getCreatedByUserId(), owner)
                    && (!"shared".equals(session.getVisibility()) || !chat.isParticipant(workspaceId, sessionId, owner)))) {
            throw new ResourceNotFoundException("Mission session inaccessible");
        }
        privacyGuard.capture(chat, session);
        return session;
    }

    private AiAgentRun requireRun(int workspaceId, int id, int sessionId) {
        AiAgentRun run = runs.lockRun(workspaceId, id);
        if (run == null || !Objects.equals(run.getSessionId(), sessionId)) {
            throw new ResourceNotFoundException("Mission inaccessible");
        }
        return run;
    }

    private void requireIdentity(AiAgentRun run, AiAgentExecutionPolicy policy) {
        if (run == null || !"running".equals(run.getStatus()) || run.getWithheldAt() != null
                || run.getExecutionGeneration() != policy.executionGeneration()
                || !Objects.equals(run.getActiveSegmentId(), policy.segmentId())) {
            throw new ConflictException("mission_state_conflict");
        }
        try {
            var snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ConflictException("mission_unavailable");
        }
    }

    private void requireAllowance(AiAgentRun run) {
        if (!now().isBefore(run.getExecutionDeadlineAt()) || !now().isBefore(run.getHardDeadlineAt())) {
            throw new ConflictException("mission_expired");
        }
        if (run.getWithheldAt() != null || run.getPurgedAt() != null) {
            throw new ConflictException("mission_unavailable");
        }
        try {
            Snapshot snapshot = codec.decode(run);
            if (snapshot != null) {
                codec.validateExecutable(run, snapshot);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ConflictException("mission_unavailable");
        }
        if (run.getReservedSteps() != 0 || run.getReservedTokens() != 0 || run.getActiveSegmentId() != null) {
            throw new ConflictException("mission_state_conflict");
        }
        if (run.getConsumedSteps() >= run.getMaxSteps() || run.getConsumedTokens() >= run.getMaxTokens()) {
            throw new ConflictException("mission_budget_exhausted");
        }
    }

    private AiChatMessage message(AiAgentRun run, int seq, String author, String kind, String content, String metadata) {
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(run.getWorkspaceId());
        message.setSessionId(Objects.requireNonNull(run.getSessionId()));
        message.setMissionRunId(run.getId());
        message.setMissionMessageKind(kind);
        message.setPrivacyGeneration(run.getPrivacyGeneration());
        message.setOrganizationPrivacyGeneration(run.getOrganizationPrivacyGeneration());
        message.setSeq(seq);
        message.setAuthorKind(author);
        message.setContent(content);
        message.setStructuredJson(metadata);
        return message;
    }

    private void update(AiAgentRun run, long version, long generation) {
        if (runs.updateRun(run, version, generation) != 1) {
            throw new ConflictException("mission_version_conflict");
        }
    }

    private void fence(AiChatPrivacyToken token, long epoch) {
        if (!restriction.retainReadFenceUntilTransactionCompletionIfCurrent(token.workspaceId(), epoch)) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
        privacyGuard.retain(chat, token);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
