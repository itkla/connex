package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatParticipant;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;

/** Workspace-scoped persistence for assistant sessions, participants, messages, turns, and tools. */
public interface AiChatMapper {
    List<AiChatSession> listAccessibleSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    long countAccessibleSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    List<AiChatSession> listInvitedSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    long countInvitedSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    List<AiChatSession> listRetainedSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("activeMemberIds") List<Integer> activeMemberIds,
        @Param("limit") int limit,
        @Param("offset") int offset);

    long countRetainedSessions(
        @Param("workspaceId") int workspaceId,
        @Param("activeMemberIds") List<Integer> activeMemberIds);

    AiChatSession getAccessibleSessionById(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    AiChatSession getRetainedSessionById(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id,
        @Param("activeMemberIds") List<Integer> activeMemberIds);

    AiChatSession getSessionById(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    AiChatSession getSessionByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

    boolean sessionExists(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);

    int insertSession(AiChatSession session);

    int updateSession(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("title") String title,
        @Param("status") String status,
        @Param("visibility") String visibility);

    int updateGeneratedTitle(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("title") String title);

    int insertParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    int insertInvitation(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId,
        @Param("invitedByUserId") int invitedByUserId);

    AiChatParticipant getParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    List<AiChatParticipant> listParticipants(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    List<Integer> listRealtimeRecipientUserIds(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int joinParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    int deleteParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    int deleteParticipantsForSession(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    void deleteParticipantsForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Deletes a user's participant grants across every workspace during account erasure. */
    void deleteParticipantsForUserAnywhere(@Param("userId") int userId);

    /** Clears invitation provenance for a permanently erased account across every workspace. */
    void clearParticipantInvitersAnywhere(@Param("userId") int userId);

    /** Clears session provenance for a permanently erased account across every workspace. */
    void clearSessionCreatorsAnywhere(@Param("userId") int userId);

    /** Clears message provenance for a permanently erased account across every workspace. */
    void clearMessageAuthorsAnywhere(@Param("userId") int userId);

    /** Clears tool-call provenance for a permanently erased account across every workspace. */
    void clearToolCallExecutorsAnywhere(@Param("userId") int userId);

    /** Clears turn provenance for a permanently erased account across every workspace. */
    void clearTurnRequestersAnywhere(@Param("userId") int userId);

    boolean isParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    int countParticipants(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int countAssistantMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int nextMessageSequence(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int insertMessage(AiChatMessage message);

    AiChatMessage getMessageById(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    List<AiChatMessage> listMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AiChatMessage> listAssistantMessagesBySessionAndTurnIds(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("turnIds") List<Integer> turnIds,
        @Param("limit") int limit);

    List<AiChatMessage> listRecentMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("maxSeq") int maxSeq,
        @Param("limit") int limit);

    AiChatMessage getHistorySummary(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    List<AiChatMessage> listMessagesForCompaction(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("afterSeq") int afterSeq,
        @Param("beforeSeq") int beforeSeq,
        @Param("limit") int limit);

    int updateHistorySummary(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("content") String content,
        @Param("structuredJson") String structuredJson,
        @Param("inputTokens") int inputTokens,
        @Param("outputTokens") int outputTokens);

    long countMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int updateLastMessageAt(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);

    int countActiveTurns(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int insertTurn(AiChatTurn turn);

    /**
     * Records which declared skill a running turn routed to.
     *
     * @param workspaceId active workspace
     * @param sessionId owning session
     * @param turnId running turn
     * @param skillKey stable catalog key
     * @param skillVersion semantic version of the declaration that ran
     * @return rows updated, zero when the turn is no longer running
     */
    int applyTurnSkill(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("turnId") int turnId,
        @Param("skillKey") String skillKey,
        @Param("skillVersion") String skillVersion);

    AiChatTurn getTurnById(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    AiChatTurn getTurnByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    List<AiChatTurn> listActiveTurnsBySessionForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    List<AiChatTurn> listTurnsByIds(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("ids") List<Integer> ids);

    AiChatTurn getLatestActiveTurnBySession(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int markTurnRunning(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    int updateTurnTerminal(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("status") String status,
        @Param("terminalReason") String terminalReason,
        @Param("expectedStatus") String expectedStatus,
        @Param("updatedBefore") LocalDateTime updatedBefore);

    int appendTurnPartialContent(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("expectedOffset") int expectedOffset,
        @Param("content") String content,
        @Param("nextOffset") int nextOffset);

    int resetTurnPartialContent(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("expectedOffset") int expectedOffset);

    int replaceTurnPartialContent(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("content") String content,
        @Param("offset") int offset);

    int cancelTurn(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    int insertToolCall(AiChatToolCall toolCall);

    AiChatToolCall getToolCallById(
        @Param("workspaceId") int workspaceId,
        @Param("messageId") int messageId,
        @Param("id") int id);

    AiChatToolCall getToolCallByIdempotencyKey(
        @Param("workspaceId") int workspaceId,
        @Param("idempotencyKey") String idempotencyKey);

    AiChatToolCall getToolCallBySession(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    List<AiChatToolCall> listPendingToolCallsBySession(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    List<AiChatToolCall> listToolCallsBySession(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("pendingOnly") boolean pendingOnly,
        @Param("limit") int limit);

    List<AiChatToolCall> listToolCallsByTurn(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("idempotencyPrefix") String idempotencyPrefix,
        @Param("limit") int limit);

    AiChatToolCall getToolCallBySessionForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id);

    int updateToolCall(
        @Param("workspaceId") int workspaceId,
        @Param("messageId") int messageId,
        @Param("id") int id,
        @Param("status") String status,
        @Param("resultJson") String resultJson,
        @Param("executedByUserId") int executedByUserId);

    int updateExecutedToolResult(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("resultJson") String resultJson,
        @Param("executedByUserId") int executedByUserId);
}
