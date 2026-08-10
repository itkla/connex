package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

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
    private static final String TIMED_OUT = "timed_out";
    private static final String GENERATION_TIMEOUT = "generation_timeout";
    private static final int RESOLVE_TIMEOUT_SECONDS = 30;
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String PROPOSED = "proposed";
    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    private final AiChatMapper chatMapper;
    private final WorkspaceService workspaceService;
    private final AiProperties aiProperties;
    private final AiRestrictionEpoch restrictionEpoch;
    private final Clock clock;

    /** Commits the user message and queued turn under the session sequence mutex. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatQueuedTurn queue(
            int sessionId, AiChatTurnCreateRequest request, long restrictionEpoch) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        AiChatSession session = requireAccessibleLocked(workspaceId, userId, sessionId);
        if (ARCHIVED.equals(session.getStatus())) {
            throw new ConflictException("Archived sessions cannot accept turns");
        }
        if (!ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Assistant session is not active");
        }
        LocalDateTime cutoff = expiryCutoff();
        chatMapper.listActiveTurnsBySessionForUpdate(workspaceId, sessionId)
                .forEach(turn -> expireIfStale(turn, cutoff));
        if (chatMapper.countActiveTurns(workspaceId, sessionId) != 0) {
            throw new ConflictException("Assistant session already has an active turn");
        }

        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(workspaceId);
        message.setSessionId(sessionId);
        message.setSeq(chatMapper.nextMessageSequence(workspaceId, sessionId));
        message.setAuthorKind(USER);
        message.setAuthorUserId(userId);
        message.setContent(request.content());
        chatMapper.insertMessage(message);

        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspaceId);
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(userId);
        turn.setStatus(QUEUED);
        chatMapper.insertTurn(turn);
        chatMapper.updateLastMessageAt(workspaceId, sessionId);
        return new AiChatQueuedTurn(
                workspaceId, userId, sessionId, turn.getId(), message.getId(),
                restrictionEpoch, request.pageContext());
    }

    /** Returns one authorized durable turn after applying its lazy generation deadline. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatTurn readTurn(int sessionId, int turnId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        requireAccessibleLocked(workspaceId, userId, sessionId);
        AiChatTurn turn = chatMapper.getTurnByIdForUpdate(workspaceId, sessionId, turnId);
        if (turn == null) {
            throw inaccessible();
        }
        return expireIfStale(turn, expiryCutoff());
    }

    /** Marks a queued turn running after re-locking membership and session authorization. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean markRunning(AiChatQueuedTurn turn) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, QUEUED);
        return chatMapper.markTurnRunning(
                turn.workspaceId(), turn.sessionId(), turn.turnId()) == 1;
    }

    /** Loads the bounded most-recent transcript after current access revalidation. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AiChatMessage> loadHistory(AiChatQueuedTurn turn, int limit) {
        requireCurrentActorRead(turn);
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.listRecentMessages(turn.workspaceId(), turn.sessionId(), limit);
    }

    /** Persists a demasked read-tool proposal before execution. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            String toolName,
            String argumentsJson) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(turn.workspaceId());
        toolCall.setMessageId(turn.userMessageId());
        toolCall.setToolName(toolName);
        toolCall.setStatus(PROPOSED);
        toolCall.setArgumentsJson(argumentsJson);
        toolCall.setIdempotencyKey("turn-" + turn.turnId() + "-step-" + stepNumber);
        chatMapper.insertToolCall(toolCall);
        return toolCall.getId();
    }

    /** Requires the turn to remain running immediately before a proposed tool executes. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void requireRunning(AiChatQueuedTurn turn) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
    }

    /** Persists one executed or failed read-tool terminal state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean finishTool(
            AiChatQueuedTurn turn,
            int toolCallId,
            String status,
            String resultJson) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        return chatMapper.updateToolCall(
                turn.workspaceId(), turn.userMessageId(), toolCallId, status,
                resultJson, turn.userId()) == 1;
    }

    /** Applies a generation-owned failed tool transition without request-thread authorization. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean failTool(
            AiChatQueuedTurn turn,
            int toolCallId,
            String resultJson) {
        return chatMapper.updateToolCall(
                turn.workspaceId(), turn.userMessageId(), toolCallId, "failed",
                resultJson, turn.userId()) == 1;
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
        requireActiveTransaction();
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        AiChatMessage message = new AiChatMessage();
        message.setWorkspaceId(turn.workspaceId());
        message.setSessionId(turn.sessionId());
        message.setSeq(chatMapper.nextMessageSequence(turn.workspaceId(), turn.sessionId()));
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
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
        return true;
    }

    /** Applies a generation-owned terminal transition without requiring request-thread state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean markTerminal(
            int workspaceId,
            int sessionId,
            int turnId,
            String status,
            String reason) {
        return chatMapper.updateTurnTerminal(
                workspaceId, sessionId, turnId, status, reason, null, null) == 1;
    }

    private static void requireActiveTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                "Assistant answer persistence requires an active transaction");
        }
    }

    private void requireCurrentActor(AiChatQueuedTurn turn) {
        if (workspaceService.getCurrentWorkspaceId() != turn.workspaceId()
                || workspaceService.getCurrentUserId() != turn.userId()) {
            throw inaccessible();
        }
        workspaceService.lockAndRequireMember(turn.workspaceId(), turn.userId());
        workspaceService.requirePermission(turn.workspaceId(), turn.userId(), Permission.AI_USE);
    }

    private void requireCurrentActorRead(AiChatQueuedTurn turn) {
        if (workspaceService.getCurrentWorkspaceId() != turn.workspaceId()
                || workspaceService.getCurrentUserId() != turn.userId()
                || !workspaceService.permissionsFor(turn.workspaceId(), turn.userId())
                        .contains(Permission.AI_USE)) {
            throw inaccessible();
        }
    }

    private AiChatTurn lockAuthorizedTurn(AiChatQueuedTurn turn, String requiredStatus) {
        AiChatSession session = requireAccessibleLocked(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (!ACTIVE.equals(session.getStatus())) {
            throw new ConflictException("Assistant session is no longer active");
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (stored == null
                || !Objects.equals(stored.getRequestedByUserId(), turn.userId())
                || !requiredStatus.equals(stored.getStatus())) {
            throw new ConflictException("Assistant turn is no longer active");
        }
        return stored;
    }

    private AiChatSession requireAccessibleLocked(int workspaceId, int userId, int sessionId) {
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

    private AiChatTurn expireIfStale(AiChatTurn turn, LocalDateTime cutoff) {
        if (!QUEUED.equals(turn.getStatus()) && !RUNNING.equals(turn.getStatus())) {
            return turn;
        }
        int changed = chatMapper.updateTurnTerminal(
                turn.getWorkspaceId(), turn.getSessionId(), turn.getId(),
                TIMED_OUT, GENERATION_TIMEOUT, turn.getStatus(), cutoff);
        if (changed == 0) {
            return turn;
        }
        AiChatTurn expired = chatMapper.getTurnByIdForUpdate(
                turn.getWorkspaceId(), turn.getSessionId(), turn.getId());
        if (expired == null) {
            throw new IllegalStateException("Expired assistant turn is unavailable");
        }
        return expired;
    }

    private LocalDateTime expiryCutoff() {
        return LocalDateTime.ofInstant(
                clock.instant().minus(aiProperties.getGenerationMaxLifetime()), ZoneOffset.UTC);
    }

    private static ResourceNotFoundException inaccessible() {
        return new ResourceNotFoundException(INACCESSIBLE);
    }
}
