package ooo.klae.connex.backend.ai.assistant;

import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.mission.AiAgentExecutionPolicy;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseService;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.services.AiWorkspaceGovernanceService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Transactional durable-state boundary for assistant turns, messages, and read-tool calls. */
@Service
@RequiredArgsConstructor
public class AiChatTurnPersistenceService {
    private static final String ACTIVE = "active";
    private static final String ARCHIVED = "archived";
    private static final String SHARED = "shared";
    private static final String QUEUED = "queued";
    private static final String RUNNING = "running";
    private static final String RESOLVED = "resolved";
    private static final String CANCELLED = "cancelled";
    private static final String TIMED_OUT = "timed_out";
    private static final String GENERATION_TIMEOUT = "generation_timeout";
    private static final int RESOLVE_TIMEOUT_SECONDS = 30;
    private static final int MAX_USER_IDENTIFIERS = 20;
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String SYSTEM = "system";
    private static final String PROPOSED = "proposed";
    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    private final AiChatPrivacyGuard privacyGuard;
    private final AiChatMapper chatMapper;
    private final AttachmentMapper attachmentMapper;
    private final WorkspaceService workspaceService;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiWorkspaceGovernanceService governanceService;
    private final AiAssistantIdentifierResolver identifierResolver;
    private final AiAssistantToolExecutor toolExecutor;
    private final AiAssistantSessionReadAudit sessionReadAudit;
    private final Clock clock;
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final ObjectMapper objectMapper;
    private final AiRunLeaseService runLeaseService;

    private final RecordVisibilityScopeControlAccess recordVisibilityScopeControlAccess;

    /** Commits the user message and queued turn under the session sequence mutex. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatQueuedTurn queue(
            int sessionId, AiChatTurnCreateRequest request, long restrictionEpoch) {
        return queue(
                sessionId, request, restrictionEpoch, AiPrivacyMode.MASKED, false);
    }

    /** Commits a user message and turn with its immutable privacy and delivery posture. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatQueuedTurn queue(
            int sessionId,
            AiChatTurnCreateRequest request,
            long restrictionEpoch,
            AiPrivacyMode privacyMode,
            boolean streamed) {
        return queue(
                sessionId, request, restrictionEpoch, privacyMode, streamed,
                AiChatQueryScope.none(), null);
    }

    /**
     * Commits a user message and turn together with the exact query scope it was authorized to
     * read, so a later read of the turn restates the same breadth the requester was shown.
     *
     * @param sessionId owning session
     * @param request validated turn request
     * @param restrictionEpoch processing-restriction fence captured before admission
     * @param privacyMode immutable privacy posture
     * @param streamed whether terminal text is streamed
     * @param scope validated declared query scope
     * @param interpretedScopeJson serialized interpreted scope echo, or null when none was declared
     * @return committed durable preparation
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatQueuedTurn queue(
            int sessionId,
            AiChatTurnCreateRequest request,
            long restrictionEpoch,
            AiPrivacyMode privacyMode,
            boolean streamed,
            AiChatQueryScope scope,
            String interpretedScopeJson) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireActiveAiAccess(workspaceId, userId);
        if (!request.pageContext().isEmpty()) {
            recordVisibilityScopeControlAccess.getForWorkspace(workspaceId);
        }
        sessionReadAudit.retainTranscriptAuditParents(workspaceId, userId);
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        AiChatSession session = requireAccessibleLocked(workspaceId, userId, sessionId);
        requireActiveAuthor(session, activeMemberIds);
        if (ARCHIVED.equals(session.getStatus())) {
            throw new ConflictException("Archived sessions cannot accept turns");
        }
        if (!ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Assistant session is not active");
        }
        LocalDateTime cutoff = expiryCutoff();
        List<AiChatTurn> activeTurns = chatMapper.listActiveTurnIdsBySession(workspaceId, sessionId)
                .stream().sorted()
                .map(id -> chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, id))
                .filter(Objects::nonNull).toList();
        List<Integer> expiredTurnIds = activeTurns.stream()
                .filter(turn -> expireStaleTurn(turn, cutoff)).map(AiChatTurn::getId).toList();
        if (chatMapper.countActiveTurns(workspaceId, sessionId) != 0) {
            throw new ConflictException("Assistant session already has an active turn");
        }
        AiChatPrivacyToken privacyToken = privacyGuard.capture(chatMapper, session);
        List<Integer> attachmentIds = attachmentMapper.getAssistantSessionAttachments(
                workspaceId, sessionId, privacyGuard.currentGeneration(workspaceId)).stream()
                .map(Attachment::getId)
                .toList();
        if (request.pageContext().size() + attachmentIds.size()
                > AiChatAttachmentPolicy.MAX_ATTACHMENTS) {
            throw new BadRequestException(
                    "Assistant turns accept at most ten record and file context items");
        }

        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspaceId);
        message.setSessionId(sessionId);
        message.setSeq(chatMapper.nextMessageSequence(workspaceId, sessionId));
        message.setPrivacyGeneration(privacyToken.privacyGeneration());
        message.setOrganizationPrivacyGeneration(privacyToken.organizationPrivacyGeneration());
        message.setAuthorKind(USER);
        message.setAuthorUserId(userId);
        message.setContent(request.content());
        AiChatResourceRegistry messageResources = new AiChatResourceRegistry();
        if (!request.pageContext().isEmpty()) {
            toolExecutor.pageContext(request.pageContext(), messageResources);
        }
        AiAssistantIdentifierResolver.Resolution identifierResolution =
                identifierResolver.resolve(request.content());
        identifierResolution.resources().forEach(resource ->
                messageResources.register(resource.kind(), resource.id()));
        var identifiers = new ArrayList<>(identifierResolution.identifiers());
        var exactSuggestionMembers = new java.util.LinkedHashSet<String>();
        AiChatMessage latestAnswer = chatMapper.getLatestAnswer(workspaceId, sessionId, privacyGuard.currentGeneration(workspaceId));
        if (latestAnswer != null && latestAnswer.getStructuredJson() != null) {
            try {
                AiAssistantMemberIdentifiers.suggestionIdentifiers(
                        objectMapper.readTree(latestAnswer.getStructuredJson()), request.content())
                        .forEach(identifier -> {
                            exactSuggestionMembers.add(identifier.value());
                            var member = new AiAssistantIdentifierResolver.Identifier(
                                    ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER, identifier.value());
                            if (!identifiers.contains(member)) {
                                identifiers.add(member);
                            }
                        });
            } catch (JacksonException exception) {
                throw new IllegalStateException("Assistant suggestion provenance could not be read", exception);
            }
        }
        if (identifiers.size() > MAX_USER_IDENTIFIERS) {
            throw AiAssistantLoopException.malformed("identifier_limit_exceeded");
        }
        message.setStructuredJson(userMessageMetadata(
                messageResources.snapshot(), List.copyOf(identifiers), exactSuggestionMembers));
        chatMapper.insertMessage(message);

        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspaceId);
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(userId);
        turn.setStatus(QUEUED);
        turn.setScopeJson(interpretedScopeJson);
        turn.setPrivacyMode(privacyMode.name().toLowerCase(java.util.Locale.ROOT));
        turn.setStreamed(streamed);
        turn.setPrivacyGeneration(privacyToken.privacyGeneration());
        turn.setOrganizationPrivacyGeneration(privacyToken.organizationPrivacyGeneration());
        turn.setPrivacyTaintSeq(privacyToken.taintSeqAtAdmission());
        chatMapper.insertTurn(turn);
        chatMapper.updateLastMessageAt(workspaceId, sessionId);
        realtimeDispatcher.sessionAfterCommit(
                workspaceId,
                sessionId,
                new AiChatStepFrameDto(
                        workspaceId, sessionId, turn.getId(), message.getSeq(),
                        "message", null, "created", null));
        if (!this.restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(workspaceId, restrictionEpoch)) {
            throw new AiAssistantLoopException("restrictions_changed", "restrictions_changed");
        }
        privacyGuard.retain(chatMapper, privacyToken);
        sessionReadAudit.recordAccessible(workspaceId, userId, session);
        expiredTurnIds.forEach(expiredId -> releaseRunLease(workspaceId, expiredId));
        return new AiChatQueuedTurn(
                workspaceId, userId, sessionId, turn.getId(), message.getId(),
                message.getSeq(), restrictionEpoch,
                !SHARED.equals(session.getVisibility()),
                request.pageContext(), attachmentIds, privacyMode, streamed, scope, privacyToken);
    }

    /**
     * Records the declared skill a running turn routed to.
     *
     * <p>Written before the plan executes so a turn that later fails still names the declaration
     * that produced it, which is what makes a failure attributable to a specific skill version.
     *
     * <p><strong>The returned flag cannot be false for a caller that got here.</strong> The update
     * is predicated on the turn still being {@code running}, and this same transaction takes the
     * turn's row lock through {@code lockAuthorizedTurn(turn, RUNNING)} first, so the predicate
     * cannot then miss. The agent loop relies on that: it seeds the turn's loaded toolsets beside
     * this call without branching on the result, and a zero-row update would leave a turn running a
     * seeded vocabulary while {@code ai_chat_turn.skill_key} stayed null — a set no reader could
     * reconstruct. A caller that reaches this method without holding that lock breaks the
     * invariant and must consume the flag instead.
     *
     * @param turn running turn
     * @param skillKey stable catalog key
     * @param skillVersion semantic version of the declaration
     * @return whether the running turn accepted the attribution
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean applySkill(AiChatQueuedTurn turn, String skillKey, String skillVersion) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        boolean applied = chatMapper.applyTurnSkill(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                skillKey, skillVersion) == 1;
        retainPrivacyFence(turn);
        return applied;
    }

    /**
     * Returns one authorized durable turn after applying its lazy generation deadline.
     *
     * <p>Deliberately not audited. This method holds the caller's membership, session and turn
     * locks, and an audit append then takes shared locks on the actor, workspace and organization
     * rows — the opposite order from a membership mutation, which is a deadlock cycle on a route
     * the client polls once per second. The {@code partialContent} it projects is a view of the
     * transcript whose disclosure is already recorded at the session read. The privacy barrier
     * remains shared through the calling turn service's complete DTO projection; provenance is
     * reloaded after acquiring it so an earlier invalidation always withholds partials and scope.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatTurn readTurn(int sessionId, int turnId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireActiveAiAccess(workspaceId, userId);
        refuseDiscoveredMission(workspaceId, userId, sessionId, turnId);
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        AiChatSession session = requireVisibleLocked(workspaceId, userId, sessionId);
        requireActiveAuthor(session, activeMemberIds);
        AiChatTurn turn = chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
        if (turn == null) {
            throw inaccessible();
        }
        refuseMissionTurn(turn);
        boolean expired = expireStaleTurn(turn, expiryCutoff());
        AiChatTurn result = turn;
        if (expired) {
            retainTerminalPrivacy(result);
        }
        privacyGuard.retainOrganization(workspaceId, session.getOrganizationPrivacyGeneration());
        chatMapper.lockPrivacyBarrierForShare(workspaceId);
        if (!turnTokenCurrent(result)) {
            result.setPartialContent(null);
            result.setPartialContentUtf16Offset(0);
            result.setScopeJson(null);
        }
        if (expired) {
            releaseRunLease(workspaceId, turnId);
        }
        return result;
    }

    /**
     * Marks a queued turn running after re-locking membership and session authorization, and
     * claims its run lease in the same transaction.
     *
     * <p>The claim and the lease share one transaction so that a claim which rolls back leaves no
     * lease row, and a committed claim always leaves one. That is the half of the coverage
     * invariant this method owns: from here until a durable terminal write tombstones it, the turn
     * has a lease row whose expiry bounds how long an abandoned turn can sit running.
     *
     * <p>The compare-and-set cannot lose: {@code lockAuthorizedTurn} holds the turn's row lock and
     * already refuses a turn whose stored status is not queued, so a second claimant serializes
     * behind it and throws before reaching the update. A zero row count is therefore an invariant
     * violation rather than a contended claim, and it fails loudly.
     *
     * <p>The caller's ownership flag travels in because the lease claim is where its self-fence
     * has to be anchored: this method waits behind the membership and turn row locks before the
     * lease row is written at all, so the fence must start when MySQL starts counting the
     * lifetime — not before that wait, and not whenever the worker next gets scheduled after it.
     *
     * @param turn the committed queued turn
     * @param ownership the caller's ownership flag, anchored on the instant the lease is written
     * @return the fencing token this instance now holds for the turn
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiRunLease markRunning(AiChatQueuedTurn turn, AiRunLeaseGuard ownership) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, QUEUED);
        if (chatMapper.markTurnRunning(
                turn.workspaceId(), turn.sessionId(), turn.turnId()) != 1) {
            throw new IllegalStateException("Assistant turn claim lost its durable state");
        }
        retainPrivacyFence(turn);
        return runLeaseService.acquireInCurrentTransaction(
                leaseKey(turn.workspaceId(), turn.turnId()), ownership);
    }

    /** Loads the bounded most-recent transcript after current access revalidation. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AiChatMessage> loadHistory(AiChatQueuedTurn turn, int limit) {
        requireCurrentActorRead(turn);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.listRecentMessages(
                turn.workspaceId(), turn.sessionId(), turn.userMessageSeq(), limit, privacyGuard.currentGeneration(turn.workspaceId()));
    }

    /** Loads the single durable session summary after current access revalidation. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AiChatMessage loadHistorySummary(AiChatQueuedTurn turn) {
        requireCurrentActorRead(turn);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.getHistorySummary(turn.workspaceId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
    }

    /** Loads whole chronological transcript messages eligible for the next compaction step. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AiChatMessage> loadCompactionCandidates(
            AiChatQueuedTurn turn,
            int afterSeq,
            int beforeSeq,
            int limit) {
        requireCurrentActorRead(turn);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.listMessagesForCompaction(
                turn.workspaceId(), turn.sessionId(), afterSeq, beforeSeq, limit, privacyGuard.currentGeneration(turn.workspaceId()));
    }

    /** Inserts or incrementally updates the durable demasked session summary. */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRES_NEW,
        timeout = RESOLVE_TIMEOUT_SECONDS)
    public AiChatMessage upsertHistorySummary(
            AiChatQueuedTurn turn,
            Integer existingSummaryId,
            int expectedThroughSeq,
            String content,
            String structuredJson,
            int inputTokens,
            int outputTokens) {
        requireActiveTransaction();
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
        AiChatMessage currentSummary = chatMapper.getHistorySummary(
                turn.workspaceId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (existingSummaryId == null) {
            if (expectedThroughSeq != 0 || currentSummary != null) {
                throw new ConflictException("Assistant history summary changed during compaction");
            }
        } else if (currentSummary == null
                || currentSummary.getId() != existingSummaryId
                || historySummaryThroughSeq(currentSummary) != expectedThroughSeq) {
            throw new ConflictException("Assistant history summary changed during compaction");
        }
        int messageId;
        if (existingSummaryId == null) {
            AiChatMessage summary = new AiChatMessage();
            summary.setWorkspaceId(turn.workspaceId());
            summary.setSessionId(turn.sessionId());
            summary.setSeq(chatMapper.nextMessageSequence(
                    turn.workspaceId(), turn.sessionId()));
            summary.setPrivacyGeneration(turn.privacyToken().privacyGeneration());
            summary.setOrganizationPrivacyGeneration(turn.privacyToken().organizationPrivacyGeneration());
            summary.setAuthorKind(SYSTEM);
            summary.setAuthorUserId(null);
            summary.setContent(content);
            summary.setStructuredJson(structuredJson);
            summary.setInputTokens(inputTokens);
            summary.setOutputTokens(outputTokens);
            chatMapper.insertMessage(summary);
            messageId = summary.getId();
            realtimeDispatcher.sessionAfterCommit(
                    turn.workspaceId(), turn.sessionId(), new AiChatStepFrameDto(
                            turn.workspaceId(), turn.sessionId(), turn.turnId(), summary.getSeq(),
                            "message", null, "created", null));
        } else {
            AiChatMessage existing = chatMapper.getMessageById(
                    turn.workspaceId(), turn.sessionId(), existingSummaryId, privacyGuard.currentGeneration(turn.workspaceId()));
            if (existing == null || !SYSTEM.equals(existing.getAuthorKind())) {
                throw new IllegalStateException("Assistant history summary is unavailable");
            }
            if (chatMapper.updateHistorySummary(
                    turn.workspaceId(), turn.sessionId(), existingSummaryId,
                    content, structuredJson, inputTokens, outputTokens) != 1) {
                throw new IllegalStateException("Assistant history summary update was lost");
            }
            messageId = existingSummaryId;
        }
        AiChatMessage stored = chatMapper.getMessageById(
                turn.workspaceId(), turn.sessionId(), messageId, privacyGuard.currentGeneration(turn.workspaceId()));
        if (stored == null) {
            throw new IllegalStateException("Assistant history summary is unavailable");
        }
        retainPrivacyFence(turn);
        return stored;
    }

    private int historySummaryThroughSeq(AiChatMessage summary) {
        if (!SYSTEM.equals(summary.getAuthorKind()) || summary.getStructuredJson() == null) {
            throw new IllegalStateException("Assistant history summary metadata is invalid");
        }
        try {
            var metadata = objectMapper.readTree(summary.getStructuredJson());
            var kind = metadata.get("kind");
            var throughSeq = metadata.get("throughSeq");
            if (kind == null || !kind.isString() || !"history_summary".equals(kind.asString())
                    || throughSeq == null || !throughSeq.isIntegralNumber()
                    || !throughSeq.canConvertToInt() || throughSeq.asInt() <= 0) {
                throw new IllegalStateException("Assistant history summary metadata is invalid");
            }
            return throughSeq.asInt();
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant history summary metadata is invalid", exception);
        }
    }

    /** Loads current session-private attachment metadata after fresh actor authorization. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Attachment> loadAttachments(AiChatQueuedTurn turn) {
        requireCurrentActorRead(turn);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (session == null) {
            throw inaccessible();
        }
        if (turn.attachmentIds().isEmpty()) {
            return List.of();
        }
        List<Attachment> attachments = attachmentMapper.getAssistantSessionAttachmentsByIds(
                turn.workspaceId(), turn.sessionId(), turn.attachmentIds(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (attachments.size() != turn.attachmentIds().size()) {
            throw inaccessible();
        }
        return attachments;
    }

    /**
     * Persists a demasked read-tool proposal before execution.
     *
     * @param turn the running turn
     * @param stepNumber the durable model-step number
     * @param callOrdinal the call's position in its step, 0 when it is the step's only call
     * @param toolName the declared tool the call named
     * @param argumentsJson the demasked arguments the model proposed
     * @return the durable tool-call row id
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            int callOrdinal,
            String toolName,
            String argumentsJson) {
        return proposeTool(turn, stepNumber, callOrdinal, toolName, argumentsJson, null);
    }

    /**
     * Persists a demasked read-tool proposal with optional opaque provider replay state.
     *
     * @param turn the running turn
     * @param stepNumber the durable model-step number
     * @param callOrdinal the call's position in its step, 0 when it is the step's only call
     * @param toolName the declared tool the call named
     * @param argumentsJson the demasked arguments the model proposed
     * @param thoughtSignature opaque provider replay state, or null
     * @return the durable tool-call row id
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            int callOrdinal,
            String toolName,
            String argumentsJson,
            String thoughtSignature) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            return mission.persistence().proposeTool(mission, stepNumber, callOrdinal, toolName, argumentsJson);
        }
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(turn.workspaceId());
        toolCall.setPrivacyGeneration(turn.privacyToken().privacyGeneration());
        toolCall.setOrganizationPrivacyGeneration(turn.privacyToken().organizationPrivacyGeneration());
        toolCall.setMessageId(turn.userMessageId());
        toolCall.setToolName(toolName);
        toolCall.setStatus(PROPOSED);
        toolCall.setArgumentsJson(argumentsJson);
        toolCall.setThoughtSignature(thoughtSignature);
        toolCall.setIdempotencyKey(turnStepKey(turn.turnId(), stepNumber, callOrdinal));
        chatMapper.insertToolCall(toolCall);
        retainPrivacyFence(turn);
        return toolCall.getId();
    }

    /**
     * Reads the stored arguments of the write proposal that already holds one step's key.
     *
     * <p>The loop reads them before the step prepares its call, because a step whose proposal is
     * already durable must replay that proposal rather than prepare a new one: a confirm-tier
     * proposal stores the resolution and principals its card was reviewed against, and resolving
     * them afresh against the workspace as it is now would disagree with the stored row after any
     * rename or offboarding in between. The arguments are only an input to {@link
     * #proposeWriteTool}, which still decides under its own locks whether the call is the stored
     * proposal. The read takes no lock.
     *
     * @param turn the running turn
     * @param stepNumber the durable model-step number
     * @return the stored arguments, or empty when no proposal holds the step's key yet
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<String> storedWriteArguments(AiChatQueuedTurn turn, int stepNumber) {
        requireCurrentActorRead(turn);
        privacyGuard.retain(chatMapper, turn.privacyToken());
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (session == null) {
            throw inaccessible();
        }
        return Optional.ofNullable(chatMapper.getToolCallByIdempotencyKey(
                        turn.workspaceId(),
                        turnStepKey(turn.turnId(), stepNumber, AiAssistantToolCallRef.SOLE_CALL), privacyGuard.currentGeneration(turn.workspaceId())))
                .map(AiChatToolCall::getArgumentsJson);
    }

    /** Persists or replays one validated write proposal under its caller-retained key. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiAssistantToolProposal proposeWriteTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            AiAssistantPreparedWrite write) {
        return proposeWriteTool(turn, stepNumber, write, null);
    }

    /** Persists or replays a validated write proposal with optional opaque provider replay state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiAssistantToolProposal proposeWriteTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            AiAssistantPreparedWrite write,
            String thoughtSignature) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        String idempotencyKey = turnStepKey(
                turn.turnId(), stepNumber, AiAssistantToolCallRef.SOLE_CALL);
        AiChatToolCall existing = chatMapper.getToolCallByIdempotencyKey(
                turn.workspaceId(), idempotencyKey, privacyGuard.currentGeneration(turn.workspaceId()));
        if (existing != null) {
            if (existing.getSessionId() != turn.sessionId()
                    || !Objects.equals(existing.getRequestedByUserId(), turn.userId())
                    || !write.toolName().equals(existing.getToolName())
                    || !sameJson(write.argumentsJson(), existing.getArgumentsJson())) {
                throw new ConflictException("Assistant tool idempotency key was reused");
            }
            retainPrivacyFence(turn);
            return new AiAssistantToolProposal(
                    existing.getId(), existing.getStatus(), existing.getResultJson(), false);
        }
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(turn.workspaceId());
        toolCall.setPrivacyGeneration(turn.privacyToken().privacyGeneration());
        toolCall.setOrganizationPrivacyGeneration(turn.privacyToken().organizationPrivacyGeneration());
        toolCall.setMessageId(turn.userMessageId());
        toolCall.setToolName(write.toolName());
        toolCall.setStatus(PROPOSED);
        toolCall.setArgumentsJson(write.argumentsJson());
        toolCall.setThoughtSignature(thoughtSignature);
        toolCall.setIdempotencyKey(idempotencyKey);
        toolCall.setServerProposal(true);
        chatMapper.insertToolCall(toolCall);
        retainPrivacyFence(turn);
        return new AiAssistantToolProposal(toolCall.getId(), PROPOSED, null, true);
    }

    /**
     * Renders the durable idempotency key one tool call owns.
     *
     * <p>The grammar is {@link AiAssistantToolCallKey}'s alone: the sole call of a step keeps the
     * exact unsuffixed key this service has always written, and only a call that shared its step
     * renders the {@code -call-k} suffix.
     *
     * @param turnId the durable turn id
     * @param stepNumber the durable model-step number
     * @param callOrdinal the call's position in its step, 0 when it is the step's only call
     * @return the durable idempotency key
     */
    private static String turnStepKey(int turnId, int stepNumber, int callOrdinal) {
        return new AiAssistantToolCallKey(turnId, stepNumber, callOrdinal).value();
    }

    private String userMessageMetadata(
            java.util.Map<String, AiChatResourceRegistry.ResourceRef> resources,
            List<AiAssistantIdentifierResolver.Identifier> identifiers,
            java.util.Set<String> exactSuggestionMembers) {
        try {
            List<java.util.Map<String, Object>> storedResources = resources.entrySet().stream()
                    .map(entry -> java.util.Map.<String, Object>of(
                            "handle", entry.getKey(),
                            "kind", entry.getValue().kind(),
                            "id", entry.getValue().id()))
                    .toList();
            List<java.util.Map<String, String>> storedIdentifiers = identifiers.stream()
                    .filter(identifier -> identifier.kind() != ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER)
                    .map(identifier -> java.util.Map.of(
                            "kind", identifier.kind().name().toLowerCase(java.util.Locale.ROOT),
                            "value", identifier.value()))
                    .toList();
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "kind", "user_message",
                    "resources", storedResources,
                    "identifiers", storedIdentifiers,
                    "memberIdentifiers", identifiers.stream()
                            .filter(identifier -> identifier.kind() == ooo.klae.connex.backend.ai.masking.EntityKind.MEMBER)
                            .map(identifier -> exactSuggestionMembers.contains(identifier.value())
                                    ? java.util.Map.of("kind", "member", "value", identifier.value())
                                    : java.util.Map.of("kind", "member", "value", identifier.value(), "matching", "mention"))
                            .toList()));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant user message metadata could not be serialized", exception);
        }
    }

    /** Requires the turn to remain running immediately before a proposed tool executes. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void requireRunning(AiChatQueuedTurn turn) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            mission.persistence().requireRunning(mission);
            return;
        }

        requireCurrentActor(turn);
        AiChatTurn stored = lockAuthorizedTurn(turn, RUNNING);
        retainRestrictionFence(turn);
        if (CANCELLED.equals(stored.getStatus())) {
            throw new AiAssistantLoopException(CANCELLED, CANCELLED);
        }
        if (!RUNNING.equals(stored.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
    }

    /** Atomically appends and publishes one decoded UTF-16-sequenced answer batch. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int appendPartialBatch(AiChatQueuedTurn turn, int expectedOffset, String content) {
        if (!turn.streamed() || content == null || content.isEmpty()) {
            throw new IllegalArgumentException("A streamed assistant text batch is required");
        }
        if (expectedOffset < 0 || expectedOffset + content.length() > 16_000) {
            throw new AiAssistantLoopException("malformed_output", "malformed_output");
        }
        AiChatTurn stored = lockAuthorizedTurn(turn, RUNNING);
        retainRestrictionFence(turn);
        if (CANCELLED.equals(stored.getStatus())) {
            throw new AiAssistantLoopException(CANCELLED, CANCELLED);
        }
        if (!RUNNING.equals(stored.getStatus())
                || stored.getPartialContentUtf16Offset() != expectedOffset) {
            throw new ConflictException("Assistant stream state changed");
        }
        int nextOffset = expectedOffset + content.length();
        if (chatMapper.appendTurnPartialContent(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                expectedOffset, content, nextOffset) != 1) {
            throw new ConflictException("Assistant stream state changed");
        }
        realtimeDispatcher.userAfterCommit(
                turn.userId(), AiChatStepFrameDto.delta(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        expectedOffset, content));
        retainPrivacyFence(turn);
        return nextOffset;
    }

    /** Clears a malformed streamed projection and invalidates clients for durable re-hydration. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void resetPartialContent(AiChatQueuedTurn turn, int expectedOffset) {
        if (!turn.streamed() || expectedOffset < 0 || expectedOffset > 16_000) {
            throw new IllegalArgumentException("A valid streamed assistant offset is required");
        }
        AiChatTurn stored = lockAuthorizedTurn(turn, RUNNING);
        if (CANCELLED.equals(stored.getStatus())) {
            throw new AiAssistantLoopException(CANCELLED, CANCELLED);
        }
        if (!RUNNING.equals(stored.getStatus())
                || stored.getPartialContentUtf16Offset() != expectedOffset
                || chatMapper.resetTurnPartialContent(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(), expectedOffset) != 1) {
            throw new ConflictException("Assistant stream state changed");
        }
        retainPrivacyFence(turn);
        realtimeDispatcher.sessionAfterCommit(
                turn.workspaceId(), turn.sessionId(), new AiChatStepFrameDto(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        0, "reset", null, RUNNING, null));
    }

    /** Cancels one active turn for its requester or session owner. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(int sessionId, int turnId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        refuseDiscoveredMission(workspaceId, userId, sessionId, turnId);
        workspaceService.lockAndRequireMember(workspaceId, userId);
        AiChatSession session = chatMapper.getSessionByIdForUpdate(
                workspaceId, userId, sessionId);
        if (session == null) {
            throw inaccessible();
        }
        boolean owner = Objects.equals(session.getCreatedByUserId(), userId);
        if (!owner && (!SHARED.equals(session.getVisibility())
                || !chatMapper.isParticipant(workspaceId, sessionId, userId))) {
            throw new ForbiddenException("Assistant turn cancellation is not permitted");
        }
        AiChatTurn turn = chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
        if (turn == null) {
            throw inaccessible();
        }
        refuseMissionTurn(turn);
        if (!owner && !Objects.equals(turn.getRequestedByUserId(), userId)) {
            throw new ForbiddenException("Assistant turn cancellation is not permitted");
        }
        if (!QUEUED.equals(turn.getStatus()) && !RUNNING.equals(turn.getStatus())) {
            throw new ConflictException("Assistant turn is already terminal");
        }
        if (chatMapper.cancelTurn(workspaceId, sessionId, turnId) != 1) {
            throw new ConflictException("Assistant turn is already terminal");
        }
        screenTerminalPartial(turn, CANCELLED);
        retainTerminalPrivacy(turn);
        releaseRunLease(workspaceId, turnId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    AiChatCancellationHooks.cancel(workspaceId, sessionId, turnId);
                }
            });
        } else {
            AiChatCancellationHooks.cancel(workspaceId, sessionId, turnId);
        }
        realtimeDispatcher.sessionAfterCommit(
                workspaceId, sessionId, new AiChatStepFrameDto(
                        workspaceId, sessionId, turnId,
                        turn.getPartialContentUtf16Offset(), "terminal", null,
                        CANCELLED, CANCELLED));
    }

    /** Persists one executed or failed read-tool terminal state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean finishTool(
            AiChatQueuedTurn turn,
            int toolCallId,
            String status,
            String resultJson) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            return mission.persistence().finishTool(mission, toolCallId, status, resultJson);
        }
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING, toolCallId);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
        boolean updated = chatMapper.updateToolCall(
                turn.workspaceId(), turn.userMessageId(), toolCallId, status,
                resultJson, turn.userId()) == 1;
        retainPrivacyFence(turn);
        return updated;
    }

    /** Applies a generation-owned failed tool transition without request-thread authorization. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean failTool(
            AiChatQueuedTurn turn,
            int toolCallId,
            String resultJson) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            return mission.persistence().finishTool(mission, toolCallId, "failed", resultJson);
        }
        AiChatTurn stored = lockGenerationOwnedTurn(turn, toolCallId);
        if (!RUNNING.equals(stored.getStatus())) {
            return false;
        }
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        boolean updated = chatMapper.updateToolCall(
                turn.workspaceId(), turn.userMessageId(), toolCallId, "failed",
                resultJson, turn.userId()) == 1;
        retainPrivacyFence(turn);
        return updated;
    }

    /**
     * Atomically appends the demasked assistant answer and resolves the locked turn.
     * Bounded by an explicit timeout because this transaction retains the restriction-epoch read
     * fence through completion; an unbounded hold would delay an APPI cease-of-use mutation for
     * every workspace sharing the fence stripe.
     */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRES_NEW,
        timeout = RESOLVE_TIMEOUT_SECONDS)
    public boolean resolve(
            AiChatQueuedTurn turn,
            String content,
            String structuredJson,
            int inputTokens,
            int outputTokens) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            return mission.persistence().resolve(mission, content, structuredJson);
        }
        requireActiveTransaction();
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
        if (turn.streamed() && chatMapper.replaceTurnPartialContent(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                content, content.length()) != 1) {
            throw new IllegalStateException("Assistant stream finalization lost its durable state");
        }
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(turn.workspaceId());
        message.setSessionId(turn.sessionId());
        message.setSeq(chatMapper.nextMessageSequence(turn.workspaceId(), turn.sessionId()));
        message.setPrivacyGeneration(turn.privacyToken().privacyGeneration());
        message.setOrganizationPrivacyGeneration(turn.privacyToken().organizationPrivacyGeneration());
        message.setAuthorKind(ASSISTANT);
        message.setAuthorUserId(null);
        message.setContent(content);
        message.setStructuredJson(structuredJson);
        message.setInputTokens(inputTokens);
        message.setOutputTokens(outputTokens);
        chatMapper.insertMessage(message);
        if (chatMapper.updateTurnTerminal(
                turn.workspaceId(), turn.sessionId(), turn.turnId(), RESOLVED, null,
                RUNNING, null) != 1) {
            throw new IllegalStateException("Assistant turn resolution lost its durable state");
        }
        chatMapper.updateLastMessageAt(turn.workspaceId(), turn.sessionId());
        retainPrivacyFence(turn);
        releaseRunLease(turn.workspaceId(), turn.turnId());
        return true;
    }

    /** Applies a first-exchange title only while the session remains auto-title eligible. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean applyGeneratedTitle(AiChatQueuedTurn turn, String title) {
        if (AiAgentExecutionPolicy.forTurn(turn) != null) {
            return false;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        requireCurrentActor(turn);
        AiChatSession session = chatMapper.getSessionByIdForUpdate(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null
                || !Objects.equals(session.getCreatedByUserId(), turn.userId())
                || session.isTitleUserSet()) {
            return false;
        }
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            return false;
        }
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatTurn source = chatMapper.getTurnById(turn.workspaceId(), turn.sessionId(), turn.turnId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (source == null || !RESOLVED.equals(source.getStatus()) || source.getMissionRunId() != null) {
            return false;
        }
        boolean updated = chatMapper.updateGeneratedTitle(turn.workspaceId(), turn.sessionId(), title) == 1;
        if (updated) {
            chatMapper.stampGeneratedTitle(turn.workspaceId(), turn.sessionId(), turn.privacyToken().privacyGeneration());
        }
        retainPrivacyFence(turn);
        return updated;
    }

    /**
     * Applies a generation-owned terminal transition without requiring request-thread state.
     *
     * <p>A failed, cancelled, or timed-out streamed turn keeps its durable partial answer so the
     * requester can still read what was established before the turn stopped. The partial is
     * re-screened for suspected special-care content here because a stopped turn never reaches the
     * terminal screen the resolved path applies, and it is purged when it cannot be shown.
     *
     * <p>A turn stopped by {@link AiAssistantTerminalReasons#AUTHORIZATION_WITHDRAWN} purges its
     * partial unconditionally; malformed output also purges it. Streaming invented-reference
     * detection withdraws partials immediately, before any later provider failure. Retained text carries no resource metadata, so a later read can
     * only gate it on session access; retaining it would leave facts readable that the requester
     * lost the right to read, or that a processing restriction withdrew mid-turn.
     */
    @Transactional(
        isolation = Isolation.READ_COMMITTED,
        propagation = Propagation.REQUIRES_NEW,
        timeout = RESOLVE_TIMEOUT_SECONDS)
    public boolean markTerminal(
            AiChatQueuedTurn turn,
            String status,
            String reason) {
        AiChatTurn stored = lockGenerationOwnedTurn(turn);
        refuseMissionTurn(stored);
        if (turnTokenCurrent(stored)) {
            screenTerminalPartial(stored, reason);
        } else {
            clearTerminalContent(stored);
        }
        if (chatMapper.updateTurnTerminal(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                status, reason, null, null) != 1) {
            return false;
        }
        retainTerminalPrivacy(stored);
        releaseRunLease(turn.workspaceId(), turn.turnId());
        return true;
    }

    /**
     * Settles one abandoned turn from an instance that never owned it, inside the settler's
     * transaction.
     *
     * <p>Declared {@link Propagation#MANDATORY} deliberately. The fence that stops a revived owner
     * is the turn's own status, and it closes when the settler commits that status — not when it
     * takes the lease over. Letting this open its own transaction would split the takeover from
     * the terminal write and reopen exactly that window, so the caller must already hold the
     * transaction, the session lock, and the turn lock.
     *
     * <p>The partial answer passes the same special-care screen {@link #markTerminal} applies,
     * through the shared helper, so an ownership loss cannot retain text a generation-owned
     * failure would have purged.
     *
     * @param stored the turn, already locked by the caller
     * @param status the terminal status to write
     * @param reason the stable terminal reason
     * @return the durable terminal projection, or empty when the turn was already terminal
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AiChatDurableTerminal> settleOrphanedTurn(
            AiChatTurn stored,
            String status,
            String reason) {
        refuseMissionTurn(stored);
        if (turnTokenCurrent(stored)) {
            screenTerminalPartial(stored, reason);
        } else {
            clearTerminalContent(stored);
        }
        if (chatMapper.updateTurnTerminal(
                stored.getWorkspaceId(), stored.getSessionId(), stored.getId(),
                status, reason, null, null) != 1) {
            return Optional.empty();
        }
        retainTerminalPrivacy(stored);
        return Optional.of(new AiChatDurableTerminal(
                status, reason, stored.isStreamed() ? stored.getPartialContentUtf16Offset() : 0));
    }

    /**
     * Expires one turn that no lease covers and that has outlived the absolute turn lifetime.
     *
     * <p>This is the instance-independent form of the reader-triggered expiry, for the turns no
     * lease sweeper may touch: a queued turn whose instance died before it was ever claimed, and
     * every turn claimed by a binary that predates the lease. Both settle as today's
     * {@code timed_out}/{@code generation_timeout}, never as an ownership loss, because no
     * instance ever recorded ownership of them.
     *
     * <p>The staleness boundary is the database's, not this JVM's. The reader-triggered expiry can
     * bind its own clock because it only ever reaches the one session its caller is reading; this
     * pass runs unattended against every workspace the instance routes to, so a clock running ahead
     * of MySQL would settle live turns across the estate. {@code updated_at} is written by MySQL,
     * so MySQL is the only clock that can be compared against it safely.
     *
     * @param workspaceId tenant key
     * @param sessionId the owning session
     * @param turnId the turn
     * @param lifetimeSeconds the absolute turn lifetime, applied by MySQL
     * @return true when this call wrote the turn's terminal state
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean expireUnleasedTurn(
            int workspaceId,
            int sessionId,
            int turnId,
            int lifetimeSeconds) {
        if (chatMapper.getSessionByIdForMaintenanceUpdate(workspaceId, sessionId) == null) {
            return false;
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
        if (stored == null || stored.getMissionRunId() != null) {
            return false;
        }
        if (!QUEUED.equals(stored.getStatus()) && !RUNNING.equals(stored.getStatus())) {
            return false;
        }
        if (chatMapper.expireTurnPastLifetime(
                workspaceId, sessionId, turnId,
                TIMED_OUT, GENERATION_TIMEOUT, stored.getStatus(), lifetimeSeconds) != 1) {
            return false;
        }
        screenTerminalPartial(stored, GENERATION_TIMEOUT);
        retainTerminalPrivacy(stored);
        releaseRunLease(workspaceId, turnId);
        return true;
    }

    private void screenTerminalPartial(AiChatTurn stored, String reason) {
        if (RUNNING.equals(stored.getStatus())
                && stored.isStreamed() && stored.getPartialContentUtf16Offset() > 0
                && (AiAssistantTerminalReasons.withdrawsAuthorization(reason)
                        || "malformed_output".equals(reason)
                        || SpecialCareTextScreen.screen(stored.getPartialContent()).excluded())
                && chatMapper.resetTurnPartialContent(
                        stored.getWorkspaceId(), stored.getSessionId(), stored.getId(),
                        stored.getPartialContentUtf16Offset()) != 1) {
            throw new IllegalStateException("Assistant terminal stream reset lost its durable state");
        }
    }

    /** Returns the durable terminal projection after a generation callback settles. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiChatDurableTerminal terminalState(AiChatQueuedTurn turn) {
        AiChatTurn stored = lockGenerationOwnedTurn(turn);
        if (QUEUED.equals(stored.getStatus()) || RUNNING.equals(stored.getStatus())) {
            throw new IllegalStateException("Assistant turn is not terminal");
        }
        return new AiChatDurableTerminal(
                stored.getStatus(),
                stored.getTerminalReason(),
                stored.isStreamed() ? stored.getPartialContentUtf16Offset() : 0);
    }

    /** Validates immutable provenance without acquiring database locks. */
    public void requireExecutionCurrentNonlocking(AiChatQueuedTurn turn) {
        var mission = AiAgentExecutionPolicy.forTurn(turn);
        if (mission != null) {
            mission.persistence().requireRunning(mission);
            return;
        }
        requireCurrentActorRead(turn);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        AiChatTurn stored = chatMapper.getTurnById(turn.workspaceId(), turn.sessionId(), turn.turnId(), privacyGuard.currentGeneration(turn.workspaceId()));
        if (stored == null || stored.getMissionRunId() != null || !RUNNING.equals(stored.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
    }

    public void requirePrivacyCurrent(AiChatQueuedTurn turn) {
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
    }

    /** Installs the original provenance for every invocation in a derived execution. */
    public <T> T withPrivacyToken(AiChatQueuedTurn turn, java.util.function.Supplier<T> action) {
        return privacyGuard.execute(chatMapper, turn.privacyToken(), action);
    }

    private void retainPrivacyFence(AiChatQueuedTurn turn) {
        retainRestrictionFence(turn);
        privacyGuard.retain(chatMapper, turn.privacyToken());
    }

    private boolean turnTokenCurrent(AiChatTurn turn) {
        if (turn.getPrivacyGeneration() == null || turn.getOrganizationPrivacyGeneration() <= 0) {
            return false;
        }
        try {
            privacyGuard.requireCurrent(chatMapper, new AiChatPrivacyToken(turn.getWorkspaceId(),
                    turn.getSessionId(), turn.getPrivacyGeneration(), turn.getPrivacyTaintSeq(), privacyGuard.organizationId(turn.getWorkspaceId()), turn.getOrganizationPrivacyGeneration()));
            return true;
        } catch (AiAssistantLoopException exception) {
            return false;
        }
    }

    private void clearTerminalContent(AiChatTurn turn) {
        chatMapper.clearTurnContent(turn.getWorkspaceId(), turn.getSessionId(), turn.getId());
        turn.setPartialContent(null);
        turn.setPartialContentUtf16Offset(0);
        turn.setScopeJson(null);
    }

    private void retainTerminalPrivacy(AiChatTurn turn) {
        if (turn.getPrivacyGeneration() == null || turn.getOrganizationPrivacyGeneration() <= 0) {
            clearTerminalContent(turn);
            return;
        }
        long epoch = restrictionEpoch.current(turn.getWorkspaceId());
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(turn.getWorkspaceId(), epoch)) {
            clearTerminalContent(turn);
            return;
        }
        try {
            privacyGuard.retain(chatMapper, new AiChatPrivacyToken(turn.getWorkspaceId(),
                    turn.getSessionId(), turn.getPrivacyGeneration(), turn.getPrivacyTaintSeq(), privacyGuard.organizationId(turn.getWorkspaceId()), turn.getOrganizationPrivacyGeneration()));
        } catch (AiAssistantLoopException exception) {
            clearTerminalContent(turn);
        }
    }

    private void refuseDiscoveredMission(int workspaceId, int userId, int sessionId, int turnId) {
        if (chatMapper.getAccessibleSessionById(workspaceId, userId, sessionId, privacyGuard.currentGeneration(workspaceId)) == null) {
            return;
        }
        AiChatTurn discovered = chatMapper.getTurnById(workspaceId, sessionId, turnId, privacyGuard.currentGeneration(workspaceId));
        if (discovered != null) {
            refuseMissionTurn(discovered);
        }
    }

    private static void refuseMissionTurn(AiChatTurn turn) {
        if (turn.getMissionRunId() != null) {
            throw new ConflictException("mission_control_required");
        }
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                "Assistant answer persistence requires an active transaction");
        }
    }

    private void retainRestrictionFence(AiChatQueuedTurn turn) {
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
    }

    private void requireCurrentActor(AiChatQueuedTurn turn) {
        if (workspaceService.getCurrentWorkspaceId() != turn.workspaceId()
                || workspaceService.getCurrentUserId() != turn.userId()) {
            throw inaccessible();
        }
        requireActiveAiAccess(turn.workspaceId(), turn.userId());
        workspaceService.lockAndRequireMember(turn.workspaceId(), turn.userId());
        workspaceService.requirePermission(turn.workspaceId(), turn.userId(), Permission.AI_USE);
    }

    private void requireActiveAiAccess(int workspaceId, int userId) {
        if (!workspaceService.isMember(workspaceId, userId)) {
            throw inaccessible();
        }
        if (!governanceService.isEnabled(workspaceId)) {
            throw new ForbiddenException("AI is disabled for this workspace");
        }
    }

    private void requireCurrentActorRead(AiChatQueuedTurn turn) {
        if (workspaceService.getCurrentWorkspaceId() != turn.workspaceId()
                || workspaceService.getCurrentUserId() != turn.userId()
                || !workspaceService.isMember(turn.workspaceId(), turn.userId())
                || !workspaceService.permissionsFor(turn.workspaceId(), turn.userId())
                        .contains(Permission.AI_USE)) {
            throw inaccessible();
        }
    }

    private AiChatTurn lockAuthorizedTurn(AiChatQueuedTurn turn, String requiredStatus) {
        return lockAuthorizedTurn(turn, requiredStatus, null);
    }

    private AiChatTurn lockAuthorizedTurn(AiChatQueuedTurn turn, String requiredStatus, Integer toolCallId) {
        AiChatSession session = requireAccessibleLocked(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (!ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Assistant session is no longer active");
        }
        if (toolCallId != null) {
            lockReactiveTool(turn, toolCallId);
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (stored == null
                || !Objects.equals(stored.getRequestedByUserId(), turn.userId())
                || !requiredStatus.equals(stored.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
        refuseMissionTurn(stored);
        privacyGuard.requireCurrent(chatMapper, turn.privacyToken());
        return stored;
    }

    private AiChatTurn lockGenerationOwnedTurn(AiChatQueuedTurn turn) {
        return lockGenerationOwnedTurn(turn, null);
    }

    private AiChatTurn lockGenerationOwnedTurn(AiChatQueuedTurn turn, Integer toolCallId) {
        AiChatSession session = chatMapper.getSessionByIdForUpdate(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        if (toolCallId != null) {
            lockReactiveTool(turn, toolCallId);
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (stored == null
                || !Objects.equals(stored.getRequestedByUserId(), turn.userId())) {
            throw inaccessible();
        }
        refuseMissionTurn(stored);
        return stored;
    }

    private void lockReactiveTool(AiChatQueuedTurn turn, int toolCallId) {
        AiChatToolCall call = chatMapper.getToolCallBySessionForUpdate(turn.workspaceId(), turn.sessionId(), toolCallId);
        if (call == null || call.getMissionRunId() != null || !"reactive".equals(call.getArtifactOrigin())
                || call.getMessageId() != turn.userMessageId()) {
            throw new ConflictException("Assistant tool source changed");
        }
    }

    private AiChatSession requireAccessibleLocked(int workspaceId, int userId, int sessionId) {
        AiChatSession session = requireVisibleLocked(workspaceId, userId, sessionId);
        privacyGuard.capture(chatMapper, session);
        return session;
    }

    private AiChatSession requireVisibleLocked(int workspaceId, int userId, int sessionId) {
        AiChatSession session = chatMapper.getSessionByIdForUpdate(workspaceId, userId, sessionId);
        if (session == null) {
            throw inaccessible();
        }
        if (Objects.equals(session.getCreatedByUserId(), userId)) {
            return session;
        }
        if (!SHARED.equals(session.getVisibility())
                || !chatMapper.isParticipant(workspaceId, sessionId, userId)) {
            throw inaccessible();
        }
        return session;
    }

    private List<Integer> activeMemberIds(int workspaceId, int userId) {
        List<Integer> activeMemberIds = workspaceService.getMembers(workspaceId).stream()
            .map(user -> user.getId())
            .toList();
        if (!activeMemberIds.contains(userId)) {
            throw inaccessible();
        }
        return activeMemberIds;
    }

    private void requireActiveAuthor(AiChatSession session, List<Integer> activeMemberIds) {
        if (session.getCreatedByUserId() == null
                || !activeMemberIds.contains(session.getCreatedByUserId())) {
            throw inaccessible();
        }
    }

    private boolean expireStaleTurn(AiChatTurn turn, LocalDateTime cutoff) {
        if (turn.getMissionRunId() != null) {
            return false;
        }
        if (!QUEUED.equals(turn.getStatus()) && !RUNNING.equals(turn.getStatus())) {
            return false;
        }
        if (chatMapper.updateTurnTerminal(
                turn.getWorkspaceId(), turn.getSessionId(), turn.getId(),
                TIMED_OUT, GENERATION_TIMEOUT, turn.getStatus(), cutoff) != 1) {
            return false;
        }
        if (turnTokenCurrent(turn)) {
            screenTerminalPartial(turn, GENERATION_TIMEOUT);
        } else {
            clearTerminalContent(turn);
        }
        turn.setStatus(TIMED_OUT);
        turn.setTerminalReason(GENERATION_TIMEOUT);
        return true;
    }

    /**
     * Releases the turn's run lease inside the terminal transaction that just changed its row.
     *
     * <p>Releasing here rather than where the generation loop ends is what keeps the coverage
     * invariant true: a process killed between the loop returning and this write leaves a held,
     * expiring lease that a settler can find, not an unleased running turn nobody is bounded to.
     *
     * <p>Every caller has already changed the turn's terminal row under a predicate that only a
     * non-terminal turn matches, so reaching here is proof that no owner may act on the turn any
     * further — including when the write landed on an instance that never held the lease, which a
     * cancel or a turn poll behind a load balancer routinely does. The release therefore retires
     * the row whether or not this instance holds a token; only when it does is the release also
     * fenced on {@code (owner, epoch)}.
     *
     * <p>The fence this leaves is the turn's status, and it closes when a settler commits the
     * turn's terminal state — not when it takes the lease over. A settler that bumps the epoch in
     * one transaction and writes the turn's terminal status in a later one leaves a window in
     * which a revived owner still reads {@code running} and can settle the turn itself, retiring
     * the settler's lease. Orphan settlement must therefore take the lease over and write the
     * terminal status in a single transaction.
     */
    private void releaseRunLease(int workspaceId, int turnId) {
        runLeaseService.releaseHeldInCurrentTransaction(leaseKey(workspaceId, turnId));
    }

    private static AiRunLeaseKey leaseKey(int workspaceId, int turnId) {
        return new AiRunLeaseKey(workspaceId, AiRunLeaseSubject.CHAT_TURN, turnId);
    }

    private LocalDateTime expiryCutoff() {
        return LocalDateTime.ofInstant(
                clock.instant().minus(AiAssistantTurnBudget.DURABLE_LIFETIME), ZoneOffset.UTC);
    }

    private boolean sameJson(String left, String right) {
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Assistant tool proposal JSON is invalid", exception);
        }
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException(INACCESSIBLE);
    }
}
