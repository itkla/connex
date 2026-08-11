package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiChatMessage;
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

    int insertSession(AiChatSession session);

    int updateSession(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("title") String title,
        @Param("status") String status);

    int insertParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

    void deleteParticipantsForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Deletes a user's participant grants across every workspace during account erasure. */
    void deleteParticipantsForUserAnywhere(@Param("userId") int userId);

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

    List<AiChatMessage> listRecentMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("maxSeq") int maxSeq,
        @Param("limit") int limit);

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
        @Param("createdBefore") LocalDateTime createdBefore);

    int insertToolCall(AiChatToolCall toolCall);

    AiChatToolCall getToolCallById(
        @Param("workspaceId") int workspaceId,
        @Param("messageId") int messageId,
        @Param("id") int id);

    int updateToolCall(
        @Param("workspaceId") int workspaceId,
        @Param("messageId") int messageId,
        @Param("id") int id,
        @Param("status") String status,
        @Param("resultJson") String resultJson,
        @Param("executedByUserId") int executedByUserId);
}
