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
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
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
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final ObjectMapper objectMapper;

    /** Commits the user message and queued turn under the session sequence mutex. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatQueuedTurn queue(
            int sessionId, AiChatTurnCreateRequest request, long restrictionEpoch) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
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
        realtimeDispatcher.sessionAfterCommit(
                workspaceId,
                sessionId,
                new AiChatStepFrameDto(
                        workspaceId, sessionId, turn.getId(), message.getSeq(),
                        "message", null, "created", null));
        return new AiChatQueuedTurn(
                workspaceId, userId, sessionId, turn.getId(), message.getId(),
                message.getSeq(), restrictionEpoch,
                !SHARED.equals(session.getVisibility()),
                request.pageContext());
    }

    /** Returns one authorized durable turn after applying its lazy generation deadline. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatTurn readTurn(int sessionId, int turnId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        workspaceService.requirePermission(workspaceId, userId, Permission.AI_USE);
        List<Integer> activeMemberIds = activeMemberIds(workspaceId, userId);
        AiChatSession session = requireAccessibleLocked(workspaceId, userId, sessionId);
        requireActiveAuthor(session, activeMemberIds);
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
        return chatMapper.listRecentMessages(
                turn.workspaceId(), turn.sessionId(), turn.userMessageSeq(), limit);
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

    /** Persists or replays one validated write proposal under its caller-retained key. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AiAssistantToolProposal proposeWriteTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            AiAssistantPreparedWrite write) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        String idempotencyKey = turnStepKey(turn.turnId(), stepNumber);
        AiChatToolCall existing = chatMapper.getToolCallByIdempotencyKey(
                turn.workspaceId(), idempotencyKey);
        if (existing != null) {
            if (existing.getSessionId() != turn.sessionId()
                    || !Objects.equals(existing.getRequestedByUserId(), turn.userId())
                    || !write.toolName().equals(existing.getToolName())
                    || !sameJson(write.argumentsJson(), existing.getArgumentsJson())) {
                throw new ConflictException("Assistant tool idempotency key was reused");
            }
            return new AiAssistantToolProposal(
                    existing.getId(), existing.getStatus(), existing.getResultJson(), false);
        }
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(turn.workspaceId());
        toolCall.setMessageId(turn.userMessageId());
        toolCall.setToolName(write.toolName());
        toolCall.setStatus(PROPOSED);
        toolCall.setArgumentsJson(write.argumentsJson());
        toolCall.setIdempotencyKey(idempotencyKey);
        chatMapper.insertToolCall(toolCall);
        return new AiAssistantToolProposal(toolCall.getId(), PROPOSED, null, true);
    }

    private static String turnStepKey(int turnId, int stepNumber) {
        if (turnId <= 0
                || stepNumber <= 0
                || stepNumber > AiChatAgentLoopService.HARD_MAX_STEPS) {
            throw new IllegalArgumentException("Assistant tool turn and step must be positive");
        }
        return "turn-" + turnId + "-step-" + stepNumber;
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
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
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
        AiChatTurn stored = lockGenerationOwnedTurn(turn);
        if (!RUNNING.equals(stored.getStatus())) {
            return false;
        }
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
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
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
        return true;
    }

    /** Applies a first-exchange title only while the session remains auto-title eligible. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean applyGeneratedTitle(AiChatQueuedTurn turn, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
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
        return chatMapper.updateGeneratedTitle(
                turn.workspaceId(), turn.sessionId(), title) == 1;
    }

    /** Applies a generation-owned terminal transition without requiring request-thread state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public boolean markTerminal(
            AiChatQueuedTurn turn,
            String status,
            String reason) {
        lockGenerationOwnedTurn(turn);
        return chatMapper.updateTurnTerminal(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                status, reason, null, null) == 1;
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

    private AiChatTurn lockGenerationOwnedTurn(AiChatQueuedTurn turn) {
        AiChatSession session = chatMapper.getSessionByIdForUpdate(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        AiChatTurn stored = chatMapper.getTurnByIdForUpdate(
                turn.workspaceId(), turn.sessionId(), turn.turnId());
        if (stored == null
                || !Objects.equals(stored.getRequestedByUserId(), turn.userId())) {
            throw inaccessible();
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
