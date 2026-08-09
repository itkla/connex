package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;

/** Workspace-scoped persistence for assistant sessions, participants, and messages. */
public interface AiChatMapper {
    List<AiChatSession> listAccessibleSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    long countAccessibleSessions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    AiChatSession getAccessibleSessionById(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("id") int id);

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

    void deleteOwnedSessionsForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    boolean isParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("userId") int userId);

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

    long countMessages(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId);

    int updateLastMessageAt(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);
}
