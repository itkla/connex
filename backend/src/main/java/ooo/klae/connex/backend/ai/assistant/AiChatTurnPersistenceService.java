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
import org.springframework.transaction.support.TransactionSynchronization;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiPrivacyMode;
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
    private static final String USER = "user";
    private static final String ASSISTANT = "assistant";
    private static final String SYSTEM = "system";
    private static final String PROPOSED = "proposed";
    private static final String INACCESSIBLE = "AI assistant session is not accessible";

    private final AiChatMapper chatMapper;
    private final AttachmentMapper attachmentMapper;
    private final WorkspaceService workspaceService;
    private final AiRestrictionEpoch restrictionEpoch;
    private final AiWorkspaceGovernanceService governanceService;
    private final AiAssistantIdentifierResolver identifierResolver;
    private final AiAssistantToolExecutor toolExecutor;
    private final Clock clock;
    private final AiChatRealtimeDispatcher realtimeDispatcher;
    private final ObjectMapper objectMapper;

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
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireActiveAiAccess(workspaceId, userId);
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
        List<Integer> attachmentIds = attachmentMapper.getAssistantSessionAttachments(
                workspaceId, sessionId).stream()
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
        message.setStructuredJson(userMessageMetadata(
                messageResources.snapshot(), identifierResolution.identifiers()));
        chatMapper.insertMessage(message);

        AiChatTurn turn = new AiChatTurn();
        turn.setWorkspaceId(workspaceId);
        turn.setSessionId(sessionId);
        turn.setRequestedByUserId(userId);
        turn.setStatus(QUEUED);
        turn.setPrivacyMode(privacyMode.name().toLowerCase(java.util.Locale.ROOT));
        turn.setStreamed(streamed);
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
                request.pageContext(), attachmentIds, privacyMode, streamed);
    }

    /** Returns one authorized durable turn after applying its lazy generation deadline. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.AI_USE)
    public AiChatTurn readTurn(int sessionId, int turnId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        requireActiveAiAccess(workspaceId, userId);
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

    /** Loads the single durable session summary after current access revalidation. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AiChatMessage loadHistorySummary(AiChatQueuedTurn turn) {
        requireCurrentActorRead(turn);
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.getHistorySummary(turn.workspaceId(), turn.sessionId());
    }

    /** Loads whole chronological transcript messages eligible for the next compaction step. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AiChatMessage> loadCompactionCandidates(
            AiChatQueuedTurn turn,
            int afterSeq,
            int beforeSeq,
            int limit) {
        requireCurrentActorRead(turn);
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        return chatMapper.listMessagesForCompaction(
                turn.workspaceId(), turn.sessionId(), afterSeq, beforeSeq, limit);
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
                turn.workspaceId(), turn.sessionId());
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
                    turn.workspaceId(), turn.sessionId(), existingSummaryId);
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
                turn.workspaceId(), turn.sessionId(), messageId);
        if (stored == null) {
            throw new IllegalStateException("Assistant history summary is unavailable");
        }
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
        AiChatSession session = chatMapper.getAccessibleSessionById(
                turn.workspaceId(), turn.userId(), turn.sessionId());
        if (session == null) {
            throw inaccessible();
        }
        if (turn.attachmentIds().isEmpty()) {
            return List.of();
        }
        List<Attachment> attachments = attachmentMapper.getAssistantSessionAttachmentsByIds(
                turn.workspaceId(), turn.sessionId(), turn.attachmentIds());
        if (attachments.size() != turn.attachmentIds().size()) {
            throw inaccessible();
        }
        return attachments;
    }

    /** Persists a demasked read-tool proposal before execution. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            String toolName,
            String argumentsJson) {
        return proposeTool(turn, stepNumber, toolName, argumentsJson, null);
    }

    /** Persists a demasked read-tool proposal with optional opaque provider replay state. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public int proposeTool(
            AiChatQueuedTurn turn,
            int stepNumber,
            String toolName,
            String argumentsJson,
            String thoughtSignature) {
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        AiChatToolCall toolCall = new AiChatToolCall();
        toolCall.setWorkspaceId(turn.workspaceId());
        toolCall.setMessageId(turn.userMessageId());
        toolCall.setToolName(toolName);
        toolCall.setStatus(PROPOSED);
        toolCall.setArgumentsJson(argumentsJson);
        toolCall.setThoughtSignature(thoughtSignature);
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
        toolCall.setThoughtSignature(thoughtSignature);
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

    private String userMessageMetadata(
            java.util.Map<String, AiChatResourceRegistry.ResourceRef> resources,
            List<AiAssistantIdentifierResolver.Identifier> identifiers) {
        try {
            List<java.util.Map<String, Object>> storedResources = resources.entrySet().stream()
                    .map(entry -> java.util.Map.<String, Object>of(
                            "handle", entry.getKey(),
                            "kind", entry.getValue().kind(),
                            "id", entry.getValue().id()))
                    .toList();
            List<java.util.Map<String, String>> storedIdentifiers = identifiers.stream()
                    .map(identifier -> java.util.Map.of(
                            "kind", identifier.kind().name().toLowerCase(java.util.Locale.ROOT),
                            "value", identifier.value()))
                    .toList();
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "kind", "user_message",
                    "resources", storedResources,
                    "identifiers", storedIdentifiers));
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Assistant user message metadata could not be serialized", exception);
        }
    }

    /** Requires the turn to remain running immediately before a proposed tool executes. */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void requireRunning(AiChatQueuedTurn turn) {
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
        if (!owner && !Objects.equals(turn.getRequestedByUserId(), userId)) {
            throw new ForbiddenException("Assistant turn cancellation is not permitted");
        }
        if (!QUEUED.equals(turn.getStatus()) && !RUNNING.equals(turn.getStatus())) {
            throw new ConflictException("Assistant turn is already terminal");
        }
        if (chatMapper.cancelTurn(workspaceId, sessionId, turnId) != 1) {
            throw new ConflictException("Assistant turn is already terminal");
        }
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
        requireCurrentActor(turn);
        lockAuthorizedTurn(turn, RUNNING);
        if (!restrictionEpoch.retainReadFenceUntilTransactionCompletionIfCurrent(
                turn.workspaceId(), turn.restrictionEpoch())) {
            throw new AiAssistantLoopException(
                    "restrictions_changed", "restrictions_changed");
        }
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
        return chatMapper.updateGeneratedTitle(
                turn.workspaceId(), turn.sessionId(), title) == 1;
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
     * partial unconditionally. The retained text carries no resource metadata, so a later read can
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
        if (RUNNING.equals(stored.getStatus())
                && stored.isStreamed() && stored.getPartialContentUtf16Offset() > 0
                && (AiAssistantTerminalReasons.withdrawsAuthorization(reason)
                        || SpecialCareTextScreen.screen(stored.getPartialContent()).excluded())
                && chatMapper.resetTurnPartialContent(
                        turn.workspaceId(), turn.sessionId(), turn.turnId(),
                        stored.getPartialContentUtf16Offset()) != 1) {
            throw new IllegalStateException("Assistant terminal stream reset lost its durable state");
        }
        return chatMapper.updateTurnTerminal(
                turn.workspaceId(), turn.sessionId(), turn.turnId(),
                status, reason, null, null) == 1;
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
