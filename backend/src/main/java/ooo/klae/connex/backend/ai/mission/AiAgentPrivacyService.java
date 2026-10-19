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
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWatermarks;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
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
    private final ConcurrentMap<Integer, Integer> ownerCursors = new ConcurrentHashMap<>();

    /** Initializes only a previously empty workspace, preserving every existing barrier. */
    public void initialize(int workspaceId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                initializeOnce(workspaceId);
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

    /** Executes one bounded batch, with identity discovery outside its mutation transaction. */
    public boolean batch(int workspaceId, String operationKey) {
        AiAgentPrivacyWork discovered = privacyMapper.getWork(workspaceId, operationKey);
        if (discovered == null) {
            throw new ConflictException("mission_privacy_operation_missing");
        }
        if ("complete".equals(discovered.getPhase())) {
            return true;
        }
        try {
            return switch (discovered.getPhase()) {
                case "runs" -> runBatch(discovered);
                case "sessions" -> sessionBatch(discovered);
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
        if (privacyMapper.countInitializationEvidence(workspaceId) == 0) {
            return;
        }
        UUID operation = UUID.randomUUID();
        transaction().executeWithoutResult(status -> invalidateWorkspaces(
                List.of(workspaceId), operation, "offboarding", null, null));
        for (int batches = 0; batches < 10000; batches++) {
            if (batch(workspaceId, operation.toString())) {
                return;
            }
        }
        throw new ServiceUnavailableException("mission_privacy_teardown_pending");
    }

    private boolean runBatch(AiAgentPrivacyWork discovery) {
        List<Integer> ids = privacyMapper.listAffectedRunIds(discovery.getWorkspaceId(),
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
            List<AiAgentSegment> segments = runMapper.listSegments(workspaceId, runId);
            if (segments.size() > 3) {
                throw unavailable();
            }
            if (sessionId != null) {
                for (int turnId : segments.stream().map(AiAgentSegment::getTurnId)
                        .filter(Objects::nonNull).distinct().sorted().toList()) {
                    chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
                }
            }
            AiAgentSegment active = null;
            for (AiAgentSegment segment : segments) {
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
            if (run.getPrivacyGeneration() > work.getCutoffGeneration()) {
                throw new ConflictException("mission_privacy_cursor_conflict");
            }
            if (run.getPurgedAt() == null) {
                long version = run.getStateVersion();
                long generation = run.getExecutionGeneration();
                if (active != null) {
                    String prior = active.getStatus();
                    active.setStatus("cancelled");
                    active.setSettledAt(now());
                    active.setFailureReason("privacy_invalidated");
                    active.setUsageUncertain(true);
                    requireOne(runMapper.updateSegment(active, prior));
                    privacyMapper.settleAffectedTurn(workspaceId, sessionId,
                            work.getCutoffGeneration(), active.getTurnId());
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
                run.setPurgedAt(now());
                run.setFinishedAt(now());
                run.setSessionId(null);
                run.setRequestHash(null);
                run.setGoal(null);
                run.setScopeJson(null);
                run.setScopeFingerprint(null);
                run.setReportMessageId(null);
                run.setActiveSegmentId(null);
                requireOne(runMapper.updateRun(run, version, generation));
                runMapper.detachSegmentTurns(workspaceId, runId);
                privacyMapper.deleteMissionTurns(workspaceId, runId);
                runMapper.deleteSegments(workspaceId, runId);
            }
            work.setLastRunId(runId);
            save(work);
            leaseService.releaseHeldInCurrentTransaction(
                    new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.AGENT_RUN, runId));
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
        transaction().executeWithoutResult(status -> {
            if (chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId) == null) {
                throw unavailable();
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
                privacyMapper.clearSessionProjections(workspaceId, sessionId);
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
        AiAgentPrivacyBarrier barrier = privacyMapper.lockBarrierForShare(discovery.getWorkspaceId());
        if (barrier == null || !barrier.isBlocked() || barrier.getGeneration() < discovery.getInvalidatedGeneration()) {
            throw unavailable();
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
