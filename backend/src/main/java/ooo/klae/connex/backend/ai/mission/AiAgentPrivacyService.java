package ooo.klae.connex.backend.ai.mission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWatermarks;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentPrivacyMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Durable invalidation and bounded, restartable erasure of mission-derived chat holdings. */
@Service
@RequiredArgsConstructor
public class AiAgentPrivacyService {
    private static final int BATCH_SIZE = 100;
    private static final Set<String> REASONS = Set.of("restriction", "dsr", "offboarding", "account_erasure");
    private final AiAgentPrivacyMapper privacyMapper;
    private final AiAgentRunMapper runMapper;
    private final AiAgentActionMapper actionMapper;
    private final AiChatMapper chatMapper;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiRunLeaseService leaseService;
    private final PlatformTransactionManager transactionManager;
    private final WorkspaceMapper workspaceMapper;
    private final ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper lifecycleMapper;
    private final ooo.klae.connex.backend.mappers.UserMapper userMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantCatalogResolver catalogResolver;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService assistantPrivacy;
    private final ooo.klae.connex.backend.mappers.AiAssistantPrivacyControlMapper assistantControlMapper;
    private final ooo.klae.connex.backend.mappers.AttachmentMapper attachmentMapper;
    private final ooo.klae.connex.backend.mappers.AiAgentPrivacyObjectMapper privacyObjectMapper;
    private final ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper deletionQueueMapper;
    private final ooo.klae.connex.backend.storage.ManagedObjectService managedObjectService;
    private final ConcurrentMap<Integer, Integer> ownerCursors = new ConcurrentHashMap<>();

    /** Initializes only a previously empty workspace, preserving every existing barrier. */
    public void initialize(int workspaceId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                initializeOnce(workspaceId);
                reconcileAssistantOperations(workspaceId);
                return;
            } catch (org.springframework.dao.DuplicateKeyException race) {
                if (attempt != 0) {
                    throw race;
                }
            }
        }
    }

    /** Settles legacy executions before readiness; no expired process-local token is adopted. */
    public void reconcileLegacyTurns(int workspaceId) {
        while (true) {
            var legacy = Objects.requireNonNull(transaction().execute(
                    status -> privacyMapper.legacyActiveTurns(workspaceId, BATCH_SIZE)));
            if (legacy.isEmpty()) {
                return;
            }
            for (var turn : legacy) {
                transaction().executeWithoutResult(status -> {
                    if (chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, turn.sessionId()) == null) {
                        throw unavailable();
                    }
                    chatMapper.getTurnByIdForUpdate(workspaceId, turn.sessionId(), turn.id());
                    if (privacyMapper.settleLegacyTurn(workspaceId, turn.sessionId(), turn.id()) == 1) {
                        leaseService.releaseHeldInCurrentTransaction(
                                new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.CHAT_TURN, turn.id()));
                    }
                });
            }
        }
    }

    private void initializeOnce(int workspaceId) {
        transaction().executeWithoutResult(status -> {
            AiAgentPrivacyBarrier existing = privacyMapper.lockBarrier(workspaceId);
            if (existing != null) {
                if (!existing.isBlocked() && privacyMapper.countIncompleteWork(workspaceId) != 0) {
                    throw unavailable();
                }
                return;
            }
            if (privacyMapper.countInitializationEvidence(workspaceId) != 0) {
                throw unavailable();
            }
            privacyMapper.insertBarrier(workspaceId);
        });
    }

    /**
     * Joins the restriction or accepted-operation transaction; all affected barriers lock before work rows.
     * Existing operations preserve their original generation and discovery watermarks.
     * Placement is already installed before this transaction; control mappers validate it on
     * the same connection without changing the tenant work scope.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateWorkspaces(
            List<Integer> workspaceIds, UUID operationId, String reason, Long dsrId, Integer auditId) {
        if (!REASONS.contains(reason) || ("dsr".equals(reason) != (dsrId != null && auditId != null))) {
            throw new IllegalArgumentException("Invalid mission privacy operation");
        }
        List<Integer> ordered = workspaceIds.stream().distinct().sorted().toList();
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("A privacy operation needs an accepted workspace");
        }
        Integer orgId = null;
        for (int workspaceId : ordered) {
            Integer workspaceOrg = "restriction".equals(reason) || "dsr".equals(reason)
                    ? workspaceMapper.getOrgId(workspaceId)
                    : lifecycleMapper.findWorkspaceOrgIdForLifecycle(workspaceId);
            if (workspaceOrg == null || (!"account_erasure".equals(reason) && orgId != null && !orgId.equals(workspaceOrg))) {
                throw unavailable();
            }
            String catalog = catalogResolver.resolveCatalog(workspaceOrg);
            if (!Objects.equals(catalog, tenantContext.getCatalog())) {
                throw unavailable();
            }
            orgId = workspaceOrg;
        }
        restrictionEpoch.retainWriteFencesUntilTransactionCompletion(ordered);
        List<AiAgentPrivacyBarrier> barriers = new ArrayList<>();
        for (int workspaceId : ordered) {
            AiAgentPrivacyBarrier barrier = privacyMapper.lockBarrier(workspaceId);
            if (barrier == null || barrier.getGeneration() <= 0) {
                throw unavailable();
            }
            barriers.add(barrier);
        }
        for (AiAgentPrivacyBarrier barrier : barriers) {
            int workspaceId = barrier.getWorkspaceId();
            String operationKey = operationId.toString();
            byte[] fingerprint = operationFingerprint(workspaceId, operationKey, reason, dsrId, auditId);
            AiAgentPrivacyWork existing = privacyMapper.lockWork(workspaceId, operationKey);
            if (existing != null) {
                if (!MessageDigest.isEqual(fingerprint, existing.getOperationFingerprint())) {
                    throw new ConflictException("mission_privacy_operation_conflict");
                }
                continue;
            }
            if (barrier.getGeneration() == Long.MAX_VALUE) {
                throw unavailable();
            }
            restrictionEpoch.bump(workspaceId);
            AiAgentPrivacyWatermarks bounds = privacyMapper.watermarks(workspaceId);
            AiAgentPrivacyWork work = new AiAgentPrivacyWork();
            work.setWorkspaceId(workspaceId);
            work.setOperationKey(operationKey);
            work.setOperationFingerprint(fingerprint);
            work.setReason(reason);
            work.setDsrRequestId(dsrId);
            work.setControlAcceptanceAuditId(auditId);
            work.setCutoffGeneration(barrier.getGeneration());
            work.setInvalidatedGeneration(barrier.getGeneration() + 1);
            work.setMaxRunId(bounds.getMaxRunId());
            work.setMaxSessionId(bounds.getMaxSessionId());
            work.setMaxTurnId(bounds.getMaxTurnId());
            work.setMaxMessageId(bounds.getMaxMessageId());
            work.setMaxToolCallId(bounds.getMaxToolCallId());
            work.setPhase("runs");
            work.setRevision(1);
            work.setNextAttemptAt(now());
            privacyMapper.insertWork(work);
            requireOne(privacyMapper.advanceBarrier(workspaceId, barrier.getGeneration(), operationKey));
        }
    }

    /**
     * Installs original organization authority without advancing or blocking the mission barrier.
     *
     * <p>Control receipts can outlive a tenant-catalog restore. Missing or unfinished local work
     * reopens placement and organization completion until fresh residual verification succeeds.
     * Verified terminal teardown receipts remain durable after local work is swept; lifecycle
     * cleanup owns the full residual scan and C2 waits for workspace root deletion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enrollOperation(int workspaceId, ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation operation) {
        int organizationId = organizationId(workspaceId);
        if (!Objects.equals(catalogResolver.resolveCatalog(organizationId), tenantContext.getCatalog())
                || operation.getOrganizationId() != organizationId
                || !ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.CONTENT_SCOPE.equals(operation.getContentScope())) {
            throw unavailable();
        }
        var fence = assistantControlMapper.lockFence(organizationId);
        if (fence == null || fence.getGeneration() < operation.getInvalidatedGeneration()) {
            throw unavailable();
        }
        if (assistantControlMapper.verifiedTeardownWorkspace(organizationId, operation.getOperationKey(), workspaceId)) {
            return;
        }
        if (privacyMapper.lockBarrierForShare(workspaceId) == null) {
            throw unavailable();
        }
        assistantControlMapper.reconcileWorkspaceIntents(organizationId, operation.getOperationKey());
        assistantControlMapper.lockOperation(organizationId, operation.getOperationKey());
        if (assistantControlMapper.lockWorkspaceIntent(organizationId, operation.getOperationKey(), workspaceId) == null) {
            throw unavailable();
        }
        byte[] fingerprint = assistantWorkFingerprint(workspaceId, operation);
        AiAgentPrivacyWork existing = privacyMapper.lockWork(workspaceId, operation.getOperationKey());
        if (existing != null) {
            if (!isAllChat(existing) || existing.getCutoffGeneration() != operation.getCutoffGeneration()
                    || existing.getInvalidatedGeneration() != operation.getInvalidatedGeneration()
                    || !MessageDigest.isEqual(existing.getOperationFingerprint(), fingerprint)) {
                throw new ConflictException("mission_privacy_operation_conflict");
            }
        }
        if (existing == null || !"complete".equals(existing.getPhase())
                || privacyMapper.allChatResidualCount(workspaceId, existing.getCutoffGeneration(), existing.getOperationKey()) != 0) {
            assistantControlMapper.reopenOperation(organizationId, operation.getOperationKey());
            assistantControlMapper.reopenBootstrap(organizationId, operation.getOperationKey());
            assistantControlMapper.reopenWorkspace(organizationId, operation.getOperationKey(), workspaceId);
            if (existing != null && "complete".equals(existing.getPhase())) {
                existing.setPhase("runs");
                existing.setCompletedAt(null);
                existing.setNextAttemptAt(now());
                save(existing);
            }
        }
        if (existing != null) {
            return;
        }
        AiAgentPrivacyWatermarks bounds = privacyMapper.watermarks(workspaceId);
        AiAgentPrivacyWork work = new AiAgentPrivacyWork();
        work.setWorkspaceId(workspaceId);
        work.setContentScope(operation.getContentScope());
        work.setOperationKey(operation.getOperationKey());
        work.setOperationFingerprint(fingerprint);
        work.setReason(operation.getReason());
        work.setDsrRequestId(operation.getDsrRequestId());
        work.setControlAcceptanceAuditId(operation.getControlAcceptanceAuditId());
        work.setCutoffGeneration(operation.getCutoffGeneration());
        work.setInvalidatedGeneration(operation.getInvalidatedGeneration());
        work.setMaxRunId(bounds.getMaxRunId());
        work.setMaxSessionId(bounds.getMaxSessionId());
        work.setMaxTurnId(bounds.getMaxTurnId());
        work.setMaxMessageId(bounds.getMaxMessageId());
        work.setMaxToolCallId(bounds.getMaxToolCallId());
        work.setPhase("runs");
        work.setRevision(1);
        work.setNextAttemptAt(now());
        privacyMapper.insertWork(work);
    }

    /** Retains existing restriction stripes before advancing the unconditional organization fence. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateAssistantOrganization(int organizationId, List<Integer> workspaceIds,
            UUID operationId, String reason, Long dsrId, Integer auditId) {
        List<Integer> ordered = workspaceIds.stream().distinct().sorted().toList();
        restrictionEpoch.retainWriteFencesUntilTransactionCompletion(ordered);
        var operation = assistantPrivacy.invalidateOrganization(organizationId, operationId, reason, dsrId, auditId);
        for (int workspaceId : ordered) {
            restrictionEpoch.bump(workspaceId);
        }
    }

    /** Re-enumerates organization operations without replacing their accepted cutoff or identity. */
    public void reconcileAssistantOperations(int workspaceId) {
        int organizationId = organizationId(workspaceId);
        for (var operation : assistantPrivacy.operations(organizationId)) {
            transaction().executeWithoutResult(status -> {
                enrollOperation(workspaceId, operation);
            });
            reconcileAssistantCompletion(workspaceId, operation.getOperationKey());
        }
    }

    private void reconcileAssistantCompletion(int workspaceId, String operationKey) {
        transaction().executeWithoutResult(status -> assistantPrivacy.reconcileCompletion(
                organizationId(workspaceId), operationKey));
    }

    /** Executes one bounded batch, with identity discovery outside its mutation transaction. */
    public boolean batch(int workspaceId, String operationKey) {
        AiAgentPrivacyWork discovered = privacyMapper.getWork(workspaceId, operationKey);
        if (discovered == null) {
            throw new ConflictException("mission_privacy_operation_missing");
        }
        if ("complete".equals(discovered.getPhase())) {
            if (isAllChat(discovered)) {
                reconcileAssistantCompletion(workspaceId, operationKey);
            }
            return true;
        }
        try {
            return switch (discovered.getPhase()) {
                case "runs" -> runBatch(discovered);
                case "sessions" -> isAllChat(discovered) || isWorkspaceTeardown(discovered) ? allChatSessionBatch(discovered) : sessionBatch(discovered);
                case "verify" -> complete(discovered);
                default -> throw unavailable();
            };
        } catch (RuntimeException failure) {
            recordRetry(discovered);
            throw failure;
        }
    }


    /** Enrolls owner holdings before account erasure commits; cleanup retains no new owner identity. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateOwner(int ownerUserId) {
        List<Integer> workspaces = runMapper.ownerWorkspaceIdsAnywhere(ownerUserId);
        if (!workspaces.isEmpty()) {
            invalidateWorkspaces(workspaces, UUID.randomUUID(), "account_erasure", null, null);
        }
    }

    /** Clears references to permanently deleted accounts after their mission content is purged. */
    public void reconcileErasedOwners(int workspaceId) {
        List<Integer> owners = runMapper.purgedOwnerIds(workspaceId, ownerCursors.getOrDefault(workspaceId, 0), BATCH_SIZE);
        if (owners.isEmpty()) {
            ownerCursors.remove(workspaceId);
            return;
        }
        List<Integer> existing = tenantWorkScope.unroutedRead(() -> userMapper.findExistingIds(owners));
        for (int owner : owners) {
            if (!existing.contains(owner)) {
                transaction().executeWithoutResult(status -> runMapper.clearErasedOwner(workspaceId, owner));
            }
        }
        ownerCursors.put(workspaceId, owners.getLast());
    }

    /** Settles and purges mission roots before lifecycle preparations sever restrictive links. */
    public void prepareWorkspaceTeardown(int workspaceId) {
        initialize(workspaceId);
        for (var accepted : assistantPrivacy.operations(organizationId(workspaceId))) {
            if (assistantControlMapper.verifiedTeardownWorkspace(
                    accepted.getOrganizationId(), accepted.getOperationKey(), workspaceId)) {
                continue;
            }
            boolean finished = false;
            for (int batches = 0; batches < 10000; batches++) {
                if (batch(workspaceId, accepted.getOperationKey())) {
                    finished = true;
                    break;
                }
            }
            if (!finished) {
                throw new ServiceUnavailableException("mission_privacy_teardown_pending");
            }
        }
        String operation = Objects.requireNonNull(transaction().execute(status -> enrollWorkspaceTeardown(workspaceId)));
        for (int batches = 0; batches < 10000; batches++) {
            if (batch(workspaceId, operation)) {
                return;
            }
        }
        throw new ServiceUnavailableException("mission_privacy_teardown_pending");
    }

    private boolean runBatch(AiAgentPrivacyWork discovery) {
        boolean teardown = isWorkspaceTeardown(discovery);
        List<Integer> ids = teardown ? privacyMapper.workspaceTeardownRunIds(discovery.getWorkspaceId(), 1)
                : isAllChat(discovery) ? privacyMapper.allChatRunIds(discovery.getWorkspaceId(), discovery.getCutoffGeneration(), 1)
                : privacyMapper.listAffectedRunIds(discovery.getWorkspaceId(),
                        discovery.getCutoffGeneration(), discovery.getLastRunId(), discovery.getMaxRunId(), 1);
        if (ids.isEmpty()) {
            advance(discovery, work -> work.setPhase("sessions"));
            return false;
        }
        int runId = ids.getFirst();
        AiAgentRun observed = runMapper.getRun(discovery.getWorkspaceId(), runId);
        if (observed == null) {
            throw new ConflictException("mission_privacy_cursor_conflict");
        }
        transaction().executeWithoutResult(status -> {
            int workspaceId = discovery.getWorkspaceId();
            Integer sessionId = observed.getSessionId();
            if (sessionId != null && chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId) == null) {
                throw unavailable();
            }
            AiAgentRun run = runMapper.lockRun(workspaceId, runId);
            if (run == null || !Objects.equals(sessionId, run.getSessionId())) {
                throw new ConflictException("mission_privacy_cursor_conflict");
            }
            List<AiAgentAction> actions = actionMapper.listByRoot(workspaceId, runId);
            if (actions.size() > 50) {
                throw unavailable();
            }
            for (AiAgentAction action : actions.stream().sorted(java.util.Comparator.comparingInt(AiAgentAction::getId)).toList()) {
                if (actionMapper.lockAction(workspaceId, runId, action.getId()) == null) {
                    throw unavailable();
                }
            }
            List<AiChatToolCall> tools = actions.stream().filter(action -> action.getPreparationId() != null)
                    .map(action -> chatMapper.getMissionToolCallForMaintenance(workspaceId, runId, action.getId()))
                    .filter(Objects::nonNull).sorted(java.util.Comparator.comparingInt(AiChatToolCall::getId)).toList();
            for (AiChatToolCall tool : tools) {
                if (chatMapper.lockMissionToolCall(workspaceId, runId, Objects.requireNonNull(tool.getMissionActionId())) == null) {
                    throw unavailable();
                }
            }
            List<AiAgentSegment> segments = runMapper.listSegments(workspaceId, runId);
            int maxSegments = run.getRuntimeContractVersion() == 1 ? 1 : 8;
            if (segments.size() > maxSegments * 3 || segments.stream().anyMatch(segment ->
                    segment.getSegmentNo() < 1 || segment.getSegmentNo() > maxSegments
                    || segment.getAttemptNo() < 1 || segment.getAttemptNo() > 3)) {
                throw unavailable();
            }
            boolean finalActionBatch = actions.size() <= 25;
            boolean settleAttempts = finalActionBatch || run.getActiveSegmentId() != null;
            List<Integer> turnIds = segments.stream().map(AiAgentSegment::getTurnId)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            if (sessionId != null && settleAttempts) {
                for (int turnId : turnIds) {
                    chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
                }
            }
            AiAgentSegment active = null;
            for (AiAgentSegment segment : settleAttempts ? segments : List.<AiAgentSegment>of()) {
                AiAgentSegment locked = runMapper.lockSegment(workspaceId, segment.getId());
                if (locked == null || !Objects.equals(segment.getTurnId(), locked.getTurnId())) {
                    throw unavailable();
                }
                if (Objects.equals(run.getActiveSegmentId(), locked.getId())) {
                    if (locked.getTurnId() == null || sessionId == null) {
                        throw unavailable();
                    }
                    active = locked;
                }
            }
            if (run.getActiveSegmentId() != null && active == null) {
                throw unavailable();
            }
            AiAgentPrivacyWork work = lockBatch(discovery);
            if (!teardown && (isAllChat(work) ? run.getOrganizationPrivacyGeneration() : run.getPrivacyGeneration()) > work.getCutoffGeneration()) {
                throw new ConflictException("mission_privacy_cursor_conflict");
            }
            {
                long version = run.getStateVersion();
                long generation = run.getExecutionGeneration();
                if (active != null) {
                    String prior = active.getStatus();
                    active.setStatus("cancelled");
                    active.setSettledAt(now());
                    active.setFailureReason("privacy_invalidated");
                    active.setUsageUncertain(true);
                    requireOne(runMapper.updateSegment(active, prior));
                    if (isAllChat(work) || teardown) {
                        privacyMapper.settleAllChatTurn(workspaceId, sessionId, active.getTurnId());
                    } else {
                        privacyMapper.settleAffectedTurn(workspaceId, sessionId, work.getCutoffGeneration(), active.getTurnId());
                    }
                }
                run.setConsumedSteps(Math.addExact(run.getConsumedSteps(), run.getReservedSteps()));
                run.setConsumedTokens(Math.addExact(run.getConsumedTokens(), run.getReservedTokens()));
                run.setReservedSteps(0);
                run.setReservedTokens(0);
                run.setStatus("cancelled");
                run.setReasonCode("privacy_invalidated");
                run.setStateVersion(Math.incrementExact(version));
                run.setExecutionGeneration(Math.incrementExact(generation));
                run.setWithheldAt(now());
                run.setActiveSegmentId(null);
                chatMapper.withdrawPendingMissionCalls(workspaceId, runId, "privacy_invalidated", "{}");
                if (!actions.isEmpty()) {
                    actionMapper.deleteActions(workspaceId, runId, actions.stream()
                            .limit(25).map(AiAgentAction::getId).toList());
                }
                if (!finalActionBatch) {
                    requireOne(runMapper.updateRun(run, version, generation));
                    save(work);
                    if (active != null) {
                        leaseService.releaseHeldInCurrentTransaction(
                                new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.AGENT_RUN, runId));
                    }
                    return;
                }
                run.setPurgedAt(now());
                run.setFinishedAt(now());
                run.setSessionId(null);
                run.setRequestHash(null);
                run.setGoal(null);
                run.setScopeJson(null);
                run.setScopeFingerprint(null);
                run.setReportMessageId(null);
                run.setActiveSegmentId(null);
                run.setPlanSchemaVersion(null);
                run.setPlanRevision(0);
                run.setPlanJson(null);
                run.setCheckpointSchemaVersion(null);
                run.setCheckpointRevision(0);
                run.setCheckpointJson(null);
                run.setMemorySchemaVersion(null);
                run.setMemoryJson(null);
                run.setLastCommittedSegmentNo(0);
                run.setProposalMembershipRevision(0);
                run.setProposalFrozenAt(null);
                run.setProposalFirstPreparedAt(null);
                run.setReviewExpiresAt(null);
                requireOne(runMapper.updateRun(run, version, generation));
                runMapper.detachSegmentTurns(workspaceId, runId);
                if (!turnIds.isEmpty()) {
                    privacyMapper.detachMissionTurns(workspaceId, runId, turnIds);
                }
                runMapper.deleteSegments(workspaceId, runId);
            }
            work.setLastRunId(runId);
            save(work);
            if (active != null) {
                leaseService.releaseHeldInCurrentTransaction(
                        new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.AGENT_RUN, runId));
            }
        });
        return false;
    }

    private boolean sessionBatch(AiAgentPrivacyWork discovery) {
        Integer sessionId = discovery.getCurrentSessionId();
        if (sessionId == null) {
            List<Integer> ids = privacyMapper.listAffectedSessionIds(discovery.getWorkspaceId(),
                    discovery.getCutoffGeneration(), discovery.getLastSessionId(), discovery.getMaxSessionId(), 1);
            advance(discovery, work -> {
                if (ids.isEmpty()) {
                    work.setPhase("verify");
                } else {
                    work.setCurrentSessionId(ids.getFirst());
                    work.setSessionPhase("settle_turns");
                }
            });
            return false;
        }
        int workspaceId = discovery.getWorkspaceId();
        long cutoff = discovery.getCutoffGeneration();
        String phase = Objects.requireNonNull(discovery.getSessionPhase(), "sessionPhase");
        List<Integer> ids = switch (phase) {
            case "settle_turns" -> privacyMapper.listAffectedTurnIds(workspaceId, sessionId, cutoff,
                    discovery.getLastTurnId(), discovery.getMaxTurnId(), true, 1);
            case "tools" -> privacyMapper.listAffectedToolCallIds(workspaceId, sessionId, cutoff,
                    discovery.getLastToolCallId(), discovery.getMaxToolCallId(), BATCH_SIZE);
            case "messages" -> privacyMapper.listAffectedMessageIds(workspaceId, sessionId, cutoff,
                    discovery.getLastMessageId(), discovery.getMaxMessageId(), BATCH_SIZE);
            case "turns" -> privacyMapper.listAffectedTurnIds(workspaceId, sessionId, cutoff,
                    discovery.getLastTurnId(), discovery.getMaxTurnId(), false, BATCH_SIZE);
            case "title" -> List.of();
            default -> throw unavailable();
        };
        List<String> parents = "tools".equals(phase)
                ? parentKeys(workspaceId, sessionId, cutoff, false, ids)
                : List.of();
        transaction().executeWithoutResult(status -> {
            if (chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId) == null) {
                throw unavailable();
            }
            for (String key : parents) {
                privacyMapper.lockProposalParent(workspaceId, sessionId, key);
            }
            for (int id : ids) {
                if ("settle_turns".equals(phase) || "turns".equals(phase)) {
                    chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, id);
                } else if ("tools".equals(phase)) {
                    chatMapper.getToolCallBySessionForUpdate(workspaceId, sessionId, id);
                } else if ("messages".equals(phase)) {
                    privacyMapper.lockMessage(workspaceId, sessionId, id);
                }
            }
            if ("title".equals(phase)) {
                privacyMapper.clearSessionProjections(workspaceId, sessionId);
            }
            AiAgentPrivacyWork work = lockBatch(discovery);
            boolean settled = false;
            if ("settle_turns".equals(phase)) {
                if (ids.isEmpty()) {
                    work.setSessionPhase("tools");
                    work.setLastTurnId(0);
                } else {
                    settled = privacyMapper.settleAffectedTurn(workspaceId, sessionId, cutoff, ids.getFirst()) == 1;
                    work.setLastTurnId(ids.getFirst());
                }
            } else if ("tools".equals(phase)) {
                if (ids.isEmpty()) {
                    for (String key : parents) {
                        privacyMapper.deleteProposalParent(workspaceId, sessionId, key);
                    }
                    if (!parents.isEmpty()) {
                        save(work);
                        return;
                    }
                    work.setSessionPhase("messages");
                    work.setLastToolCallId(0);
                } else {
                    privacyMapper.deleteAffectedTools(workspaceId, sessionId, cutoff, ids);
                    work.setLastToolCallId(ids.getLast());
                }
            } else if ("messages".equals(phase)) {
                if (ids.isEmpty()) {
                    work.setSessionPhase("turns");
                    work.setLastMessageId(0);
                } else {
                    privacyMapper.deleteAffectedMessages(workspaceId, sessionId, cutoff, ids);
                    work.setLastMessageId(ids.getLast());
                }
            } else if ("turns".equals(phase)) {
                if (ids.isEmpty()) {
                    work.setSessionPhase("title");
                    work.setLastTurnId(0);
                } else {
                    privacyMapper.deleteAffectedTerminalTurns(workspaceId, sessionId, cutoff, ids);
                    work.setLastTurnId(ids.getLast());
                }
            } else {
                AiAgentPrivacyBarrier barrier = privacyMapper.getBarrier(workspaceId);
                if (barrier == null) {
                    throw unavailable();
                }
                privacyMapper.resetAffectedTitle(workspaceId, sessionId, cutoff, "Ask Connex", barrier.getGeneration());
                privacyMapper.updateSessionPreview(workspaceId, sessionId);
                if (privacyMapper.deleteSessionNotifications(workspaceId, sessionId, BATCH_SIZE) == BATCH_SIZE) {
                    save(work);
                    return;
                }
                if (privacyMapper.sessionResidualCount(workspaceId, sessionId, cutoff) != 0) {
                    throw unavailable();
                }
                work.setLastSessionId(sessionId);
                work.setCurrentSessionId(null);
                work.setSessionPhase(null);
            }
            save(work);
            if (settled) {
                leaseService.releaseHeldInCurrentTransaction(new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.CHAT_TURN, ids.getFirst()));
            }
        });
        return false;
    }

    private boolean complete(AiAgentPrivacyWork discovery) {
        if (isWorkspaceTeardown(discovery)) {
            return completeWorkspaceTeardown(discovery);
        }
        if (isAllChat(discovery)) {
            return completeAllChat(discovery);
        }
        Boolean complete = transaction().execute(status -> {
            AiAgentPrivacyBarrier barrier = privacyMapper.lockBarrier(discovery.getWorkspaceId());
            if (barrier == null || !barrier.isBlocked()) {
                throw unavailable();
            }
            AiAgentPrivacyWork work = lockedWork(discovery);
            long residual = privacyMapper.residualCount(work.getWorkspaceId(), work.getCutoffGeneration());
            work.setLastResidualCount(residual);
            work.setVerifiedAt(now());
            if (residual == 0) {
                work.setPhase("complete");
                work.setCompletedAt(now());
            }
            save(work);
            if (residual == 0 && privacyMapper.countIncompleteWork(work.getWorkspaceId()) == 0
                    && privacyMapper.residualCount(work.getWorkspaceId(), barrier.getGeneration() - 1) == 0) {
                requireOne(privacyMapper.unblockBarrier(work.getWorkspaceId(), barrier.getGeneration()));
            }
            return residual == 0;
        });
        return Boolean.TRUE.equals(complete);
    }

    private void advance(AiAgentPrivacyWork discovery, java.util.function.Consumer<AiAgentPrivacyWork> change) {
        transaction().executeWithoutResult(status -> {
            AiAgentPrivacyWork work = lockBatch(discovery);
            change.accept(work);
            save(work);
        });
    }

    private AiAgentPrivacyWork lockBatch(AiAgentPrivacyWork discovery) {
        if (isAllChat(discovery)) {
            var fence = assistantControlMapper.lockFenceForShare(organizationId(discovery.getWorkspaceId()));
            if (fence == null || fence.getGeneration() < discovery.getInvalidatedGeneration()) {
                throw unavailable();
            }
        }
        AiAgentPrivacyBarrier barrier = privacyMapper.lockBarrierForShare(discovery.getWorkspaceId());
        if (barrier == null || (!isAllChat(discovery) && (!barrier.isBlocked() || barrier.getGeneration() < discovery.getInvalidatedGeneration()))) {
            throw unavailable();
        }
        if (isAllChat(discovery)) {
            int organizationId = organizationId(discovery.getWorkspaceId());
            assistantControlMapper.lockOperation(organizationId, discovery.getOperationKey());
            if (assistantControlMapper.lockWorkspaceIntent(organizationId, discovery.getOperationKey(), discovery.getWorkspaceId()) == null) {
                throw unavailable();
            }
        }
        return lockedWork(discovery);
    }

    private AiAgentPrivacyWork lockedWork(AiAgentPrivacyWork discovery) {
        AiAgentPrivacyWork work = privacyMapper.lockWork(discovery.getWorkspaceId(), discovery.getOperationKey());
        if (work == null || work.getRevision() != discovery.getRevision()
                || !MessageDigest.isEqual(work.getOperationFingerprint(), discovery.getOperationFingerprint())) {
            throw new ConflictException("mission_privacy_cursor_conflict");
        }
        return work;
    }

    private void save(AiAgentPrivacyWork work) {
        long revision = work.getRevision();
        work.setRevision(Math.incrementExact(revision));
        requireOne(privacyMapper.updateWork(work, revision));
    }

    private void recordRetry(AiAgentPrivacyWork discovery) {
        transaction().executeWithoutResult(status -> {
            if (isAllChat(discovery)) {
                var fence = assistantControlMapper.lockFenceForShare(organizationId(discovery.getWorkspaceId()));
                if (fence == null) {
                    return;
                }
            }
            AiAgentPrivacyBarrier barrier = privacyMapper.lockBarrierForShare(discovery.getWorkspaceId());
            if (barrier == null) {
                return;
            }
            AiAgentPrivacyWork work = privacyMapper.lockWork(discovery.getWorkspaceId(), discovery.getOperationKey());
            if (work == null || "complete".equals(work.getPhase())) {
                return;
            }
            work.setRetryCount(Math.incrementExact(work.getRetryCount()));
            work.setLastErrorCode("cleanup_retry");
            work.setNextAttemptAt(now().plusSeconds(Math.min(300, 1L << Math.min(9, work.getRetryCount() - 1))));
            save(work);
        });
    }

    private List<String> parentKeys(int workspaceId, int sessionId, long cutoff, boolean allChat, List<Integer> ids) {
        List<String> parents = privacyMapper.proposalParentKeys(workspaceId, sessionId, cutoff, allChat, BATCH_SIZE);
        List<String> childParents = ids.isEmpty() ? List.of() : privacyMapper.toolParentKeys(workspaceId, ids);
        return java.util.stream.Stream.concat(parents.stream(), childParents.stream()).distinct().sorted().toList();
    }

    private boolean allChatSessionBatch(AiAgentPrivacyWork discovery) {
        int workspaceId = discovery.getWorkspaceId();
        boolean teardown = isWorkspaceTeardown(discovery);
        Integer sessionId = discovery.getCurrentSessionId();
        if (sessionId == null) {
            List<Integer> sessions = teardown ? privacyMapper.workspaceTeardownSessionIds(workspaceId, 1)
                    : privacyMapper.allChatSessionIds(workspaceId, discovery.getCutoffGeneration(), 1);
            advance(discovery, work -> {
                if (sessions.isEmpty()) {
                    work.setPhase("verify");
                } else {
                    work.setCurrentSessionId(sessions.getFirst());
                    work.setSessionPhase("settle_turns");
                }
            });
            return false;
        }
        String phase = Objects.requireNonNull(discovery.getSessionPhase());
        List<Integer> ids = switch (phase) {
            case "settle_turns" -> privacyMapper.allChatTurnIds(workspaceId, sessionId, true, 1);
            case "tools" -> privacyMapper.allChatToolIds(workspaceId, sessionId, BATCH_SIZE);
            case "messages" -> privacyMapper.allChatMessageIds(workspaceId, sessionId, BATCH_SIZE);
            case "turns" -> privacyMapper.allChatTurnIds(workspaceId, sessionId, false, BATCH_SIZE);
            case "title" -> List.of();
            default -> throw unavailable();
        };
        List<String> parents = "tools".equals(phase)
                ? parentKeys(workspaceId, sessionId, discovery.getCutoffGeneration(), true, ids)
                : List.of();
        var attachments = "title".equals(phase) ? privacyMapper.chatAttachments(workspaceId, sessionId, 1) : List.<ooo.klae.connex.backend.beans.Attachment>of();
        transaction().executeWithoutResult(status -> {
            var session = chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId);
            if (session == null) {
                if (privacyMapper.allChatSessionResidualCount(workspaceId, sessionId) != 0) {
                    throw unavailable();
                }
                var work = lockBatch(discovery);
                work.setCurrentSessionId(null);
                work.setSessionPhase(null);
                save(work);
                return;
            }
            if (!teardown && session.getOrganizationPrivacyGeneration() > discovery.getCutoffGeneration()) {
                throw unavailable();
            }
            for (String key : parents) {
                privacyMapper.lockProposalParent(workspaceId, sessionId, key);
            }
            for (int id : ids) {
                switch (phase) {
                    case "settle_turns", "turns" -> chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, id);
                    case "tools" -> chatMapper.getToolCallBySessionForUpdate(workspaceId, sessionId, id);
                    case "messages" -> privacyMapper.lockMessage(workspaceId, sessionId, id);
                    default -> throw unavailable();
                }
            }
            ooo.klae.connex.backend.beans.Attachment attachment = null;
            String objectKey = null;
            Integer retainedId = null;
            ooo.klae.connex.backend.storage.ObjectDeletionTask queue = null;
            if ("title".equals(phase)) {
                privacyMapper.clearSessionProjections(workspaceId, sessionId);
            }
            if (!attachments.isEmpty()) {
                var observed = attachments.getFirst();
                attachmentMapper.lockIdsByUrl(workspaceId, observed.getUrl());
                attachment = attachmentMapper.getMetadataByIdForUpdate(workspaceId, observed.getId());
                if (attachment == null || !Objects.equals(attachment.getUrl(), observed.getUrl())
                        || !"ai_chat_session".equals(attachment.getEntityType()) || attachment.getEntityId() != sessionId) {
                    throw unavailable();
                }
                objectKey = managedObjectService.managedAttachmentKey(workspaceId, attachment.getUrl()).orElseThrow(AiAgentPrivacyService::unavailable);
                retainedId = privacyMapper.retainedCrmReference(workspaceId, attachment.getUrl());
                if (retainedId == null) {
                    deletionQueueMapper.enqueue(workspaceId, objectKey, 1, now());
                    queue = deletionQueueMapper.lockByKey(workspaceId, objectKey);
                    if (queue == null) {
                        throw unavailable();
                    }
                }
            }
            AiAgentPrivacyWork work = lockBatch(discovery);
            boolean releaseTurn = false;
            switch (phase) {
                case "settle_turns" -> {
                    if (ids.isEmpty()) {
                        work.setSessionPhase("tools");
                    } else {
                        privacyMapper.settleAllChatTurn(workspaceId, sessionId, ids.getFirst());
                        releaseTurn = true;
                    }
                }
                case "tools" -> {
                    if (!ids.isEmpty()) {
                        privacyMapper.deleteAllChatTools(workspaceId, sessionId, ids);
                    } else if (!parents.isEmpty()) {
                        for (String key : parents) {
                            privacyMapper.deleteProposalParent(workspaceId, sessionId, key);
                        }
                    } else {
                        work.setSessionPhase("messages");
                    }
                }
                case "messages" -> {
                    if (ids.isEmpty()) {
                        work.setSessionPhase("turns");
                    } else {
                        privacyMapper.deleteAllChatMessages(workspaceId, sessionId, ids);
                    }
                }
                case "turns" -> {
                    if (ids.isEmpty()) {
                        work.setSessionPhase("title");
                    } else {
                        privacyMapper.deleteAllChatTurns(workspaceId, sessionId, ids);
                    }
                }
                case "title" -> {
                    if (privacyMapper.deleteSessionNotifications(workspaceId, sessionId, BATCH_SIZE) == BATCH_SIZE) {
                        save(work);
                        return;
                    }
                    if (attachment != null) {
                        if (retainedId != null) {
                            privacyObjectMapper.insertRetained(workspaceId, work.getOperationKey(), objectKey, retainedId);
                        } else {
                            privacyObjectMapper.insertPending(workspaceId, work.getOperationKey(), objectKey, Objects.requireNonNull(queue).id());
                        }
                        attachmentMapper.delete(workspaceId, attachment.getId());
                    } else {
                        if (privacyMapper.deleteAllChatParticipants(workspaceId, sessionId, BATCH_SIZE) == BATCH_SIZE) {
                            save(work);
                            return;
                        }
                        if (privacyMapper.allChatSessionResidualCount(workspaceId, sessionId) != 0) {
                            work.setSessionPhase("settle_turns");
                        } else {
                            privacyMapper.deleteAllChatSession(workspaceId, sessionId);
                            work.setLastSessionId(sessionId);
                            work.setCurrentSessionId(null);
                            work.setSessionPhase(null);
                        }
                    }
                }
                default -> throw unavailable();
            }
            save(work);
            if (releaseTurn) {
                leaseService.releaseHeldInCurrentTransaction(new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.CHAT_TURN, ids.getFirst()));
            }
        });
        return false;
    }

    private boolean completeAllChat(AiAgentPrivacyWork discovery) {
        boolean completed = Boolean.TRUE.equals(transaction().execute(status -> {
            AiAgentPrivacyWork work = lockBatch(discovery);
            long residual = privacyMapper.allChatResidualCount(work.getWorkspaceId(), work.getCutoffGeneration(), work.getOperationKey());
            work.setLastResidualCount(residual);
            work.setVerifiedAt(now());
            if (residual == 0) {
                work.setPhase("complete");
                work.setCompletedAt(now());
                var operation = assistantPrivacy.findOperation(organizationId(work.getWorkspaceId()), work.getOperationKey());
                if (operation == null) {
                    throw unavailable();
                }
                assistantPrivacy.verifyWorkspace(operation, work.getWorkspaceId(), tenantContext.getCatalog(), Math.incrementExact(work.getRevision()));
            } else if (!privacyMapper.allChatRunIds(work.getWorkspaceId(), work.getCutoffGeneration(), 1).isEmpty()) {
                work.setPhase("runs");
            } else if (!privacyMapper.allChatSessionIds(work.getWorkspaceId(), work.getCutoffGeneration(), 1).isEmpty()) {
                work.setPhase("sessions");
            } else {
                work.setNextAttemptAt(now().plusSeconds(5));
            }
            save(work);
            return residual == 0;
        }));
        if (completed) {
            reconcileAssistantCompletion(discovery.getWorkspaceId(), discovery.getOperationKey());
        }
        return completed;
    }

    /** Lifecycle authority drains every local chat while preserving the original v1 operation cutoff. */
    private String enrollWorkspaceTeardown(int workspaceId) {
        if (!"tearing_down".equals(assistantControlMapper.workspaceLifecycleState(workspaceId))) {
            throw unavailable();
        }
        UUID operation = UUID.nameUUIDFromBytes(("assistant-workspace-teardown-v1|" + workspaceId)
                .getBytes(StandardCharsets.UTF_8));
        invalidateWorkspaces(List.of(workspaceId), operation, "offboarding", null, null);
        AiAgentPrivacyWork work = privacyMapper.lockWork(workspaceId, operation.toString());
        if (work == null) {
            throw unavailable();
        }
        if ("complete".equals(work.getPhase()) && privacyMapper.workspaceTeardownResidualCount(workspaceId) != 0) {
            work.setPhase("runs");
            work.setCompletedAt(null);
            work.setNextAttemptAt(now());
            save(work);
        }
        return operation.toString();
    }

    private boolean isWorkspaceTeardown(AiAgentPrivacyWork work) {
        return !isAllChat(work) && "offboarding".equals(work.getReason())
                && "tearing_down".equals(assistantControlMapper.workspaceLifecycleState(work.getWorkspaceId()));
    }

    private boolean completeWorkspaceTeardown(AiAgentPrivacyWork discovery) {
        return Boolean.TRUE.equals(transaction().execute(status -> {
            AiAgentPrivacyWork work = lockBatch(discovery);
            long residual = privacyMapper.workspaceTeardownResidualCount(work.getWorkspaceId());
            work.setLastResidualCount(residual);
            work.setVerifiedAt(now());
            if (residual == 0) {
                work.setPhase("complete");
                work.setCompletedAt(now());
            } else if (!privacyMapper.workspaceTeardownRunIds(work.getWorkspaceId(), 1).isEmpty()) {
                work.setPhase("runs");
            } else if (!privacyMapper.workspaceTeardownSessionIds(work.getWorkspaceId(), 1).isEmpty()) {
                work.setPhase("sessions");
            }
            save(work);
            return residual == 0;
        }));
    }

    private int organizationId(int workspaceId) {
        Integer organizationId = lifecycleMapper.findWorkspaceOrgIdForLifecycle(workspaceId);
        if (organizationId == null) {
            throw unavailable();
        }
        return organizationId;
    }

    private static boolean isAllChat(AiAgentPrivacyWork work) {
        return ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.CONTENT_SCOPE.equals(work.getContentScope());
    }

    private static byte[] assistantWorkFingerprint(int workspaceId, ooo.klae.connex.backend.beans.AiAssistantPrivacyOperation operation) {
        return ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.fingerprint("assistant-work-v2|" + workspaceId + "|"
                + operation.getOperationKey() + "|" + operation.getContentScope() + "|" + operation.getReason() + "|"
                + operation.getDsrRequestId() + "|" + operation.getControlAcceptanceAuditId() + "|"
                + operation.getCutoffGeneration() + "|" + operation.getInvalidatedGeneration());
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static byte[] operationFingerprint(int workspaceId, String operation, String reason, Long dsrId, Integer auditId) {
        String canonical = "1|" + workspaceId + "|" + operation + "|" + reason + "|" + dsrId + "|" + auditId
                + ("dsr".equals(reason) ? "|organization_mission_holdings_v1" : "");
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new ConflictException("mission_privacy_cursor_conflict");
        }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("mission_privacy_unavailable");
    }
}
