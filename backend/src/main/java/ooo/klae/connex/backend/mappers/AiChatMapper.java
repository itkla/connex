package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatParticipant;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatToolCall;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.beans.AiChatTurnRef;

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

    /**
     * Locks one session for background maintenance, with no viewer to project participation for.
     *
     * <p>The background settlement passes have no request actor, so they cannot use the
     * viewer-projected {@link #getSessionByIdForUpdate} lock. They still take the session lock
     * first, because that is the documented order — {@code ai_chat_session → ai_chat_turn →
     * ai_run_lease} — and a maintenance pass that skipped it would be the one path able to
     * deadlock against every request path.
     *
     * @param workspaceId active workspace
     * @param id the session
     * @return the locked session, or null when this workspace holds no such session
     */
    AiChatSession getSessionByIdForMaintenanceUpdate(
        @Param("workspaceId") int workspaceId,
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

    /**
     * Reads one turn's status without taking a row lock.
     *
     * <p>This is the run lease heartbeat's cross-instance stop signal, read once per tick. It takes
     * no lock, so the lease leaf never joins the {@code ai_chat_session → ai_chat_turn} lock chain
     * the claim and terminal paths walk, and it is keyed by turn alone because a lease subject key
     * carries a workspace and a subject id and no session.
     *
     * @param workspaceId active workspace
     * @param id the turn
     * @return the stored status, or null when this workspace holds no such turn
     */
    String getTurnStatus(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);

    /**
     * Reads one turn's owning session without taking a row lock.
     *
     * <p>A lease subject key carries a workspace and a subject id and no session, so the orphan
     * settlement has to learn the session before it can take the session lock the documented order
     * puts first. Reading it unlocked is safe because a turn never changes session.
     *
     * @param workspaceId active workspace
     * @param id the turn
     * @return the owning session id, or null when this workspace holds no such turn
     */
    Integer getTurnSessionId(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);

    /**
     * Enumerates the next page of workspaces holding a stale turn that no lease covers, for
     * catalog-pinned background fan-out. Returns workspace references only, never tenant content.
     *
     * <p>The lifetime boundary is computed by MySQL rather than bound as a JVM instant, because it
     * is compared against {@code updated_at}, which MySQL writes. An instance whose clock ran ahead
     * would otherwise expire live turns across every workspace it routes to.
     *
     * @param afterWorkspaceId exclusive cursor; {@code 0} starts a pass
     * @param lifetimeSeconds the absolute turn lifetime, applied by MySQL
     * @param limit maximum workspace ids returned
     * @return ascending workspace ids
     */
    List<Integer> workspaceIdsWithUnleasedStaleTurns(
        @Param("afterWorkspaceId") int afterWorkspaceId,
        @Param("lifetimeSeconds") int lifetimeSeconds,
        @Param("limit") int limit);

    /**
     * Lists the session and turn references of non-terminal turns in one workspace that hold no
     * lease row and are past the absolute lifetime.
     *
     * <p>The absence of a lease row is what separates this pass from the lease sweeper, and it is
     * load-bearing rather than an optimisation. A turn claimed by an instance running a binary
     * that predates the lease — every turn in flight during a rolling deploy — has no lease and
     * must settle as today's {@code timed_out}/{@code generation_timeout}, never as an ownership
     * loss nobody can evidence. A claimed turn always has a lease row, held or tombstoned, so it
     * is never returned here.
     *
     * <p>Only the two keys the pass needs are projected. A background thread has no reason to hold
     * the model's durable partial answer or a turn's scope JSON for every stale turn in every
     * workspace it visits, and the settlement re-reads the row it locks anyway.
     *
     * @param workspaceId active workspace
     * @param lifetimeSeconds the absolute turn lifetime, applied by MySQL
     * @param limit maximum turns returned
     * @return stale unleased turn references, oldest first
     */
    List<AiChatTurnRef> findUnleasedStaleTurnRefs(
        @Param("workspaceId") int workspaceId,
        @Param("lifetimeSeconds") int lifetimeSeconds,
        @Param("limit") int limit);

    /**
     * Settles one turn that is still in {@code expectedStatus} and whose {@code updated_at} is
     * older than the absolute lifetime, with the boundary computed by MySQL.
     *
     * <p>Separate from {@link #updateTurnTerminal}'s caller-supplied {@code updatedBefore} so the
     * unattended pass compares one database-written column against that same database's clock. The
     * reader-triggered expiry keeps its JVM boundary: it settles only the session a member is
     * looking at, on that member's own request, so a skewed instance can reach only what it is
     * already serving.
     *
     * @param workspaceId active workspace
     * @param sessionId the owning session
     * @param id the turn
     * @param status the terminal status to write
     * @param terminalReason the stable terminal reason
     * @param expectedStatus the non-terminal status observed under the row lock
     * @param lifetimeSeconds the absolute turn lifetime, applied by MySQL
     * @return rows updated; {@code 0} means the turn moved on or is not yet stale
     */
    int expireTurnPastLifetime(
        @Param("workspaceId") int workspaceId,
        @Param("sessionId") int sessionId,
        @Param("id") int id,
        @Param("status") String status,
        @Param("terminalReason") String terminalReason,
        @Param("expectedStatus") String expectedStatus,
        @Param("lifetimeSeconds") int lifetimeSeconds);

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
