package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWatermarks;
import ooo.klae.connex.backend.beans.AiAgentPrivacyWork;

/** Durable invalidation and cleanup SQL; the caller owns ordered roots and atomic cursor changes. */
@Mapper
public interface AiAgentPrivacyMapper {

    /** Executes the tenant-scoped getBarrier privacy operation. */
    AiAgentPrivacyBarrier getBarrier(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped lockBarrier privacy operation. */
    AiAgentPrivacyBarrier lockBarrier(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped lockBarrierForShare privacy operation. */
    AiAgentPrivacyBarrier lockBarrierForShare(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped insertBarrier privacy operation. */
    int insertBarrier(
            @Param("workspaceId") int workspaceId);

    /** Advances once after the operation row exists; overflow fails closed. */
    int advanceBarrier(
            @Param("workspaceId") int workspaceId,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("operationKey") String operationKey);

    /** Unblocks only an unchanged generation with no unfinished operation. */
    int unblockBarrier(
            @Param("workspaceId") int workspaceId,
            @Param("expectedGeneration") long expectedGeneration);

    /** Executes the tenant-scoped countInitializationEvidence privacy operation. */
    long countInitializationEvidence(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped watermarks privacy operation. */
    AiAgentPrivacyWatermarks watermarks(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped insertWork privacy operation. */
    int insertWork(
            @Param("work") AiAgentPrivacyWork work);

    /** Executes the tenant-scoped getWork privacy operation. */
    AiAgentPrivacyWork getWork(
            @Param("workspaceId") int workspaceId,
            @Param("operationKey") String operationKey);

    /** Executes the tenant-scoped lockWork privacy operation. */
    AiAgentPrivacyWork lockWork(
            @Param("workspaceId") int workspaceId,
            @Param("operationKey") String operationKey);

    /** Executes the tenant-scoped dueWork privacy operation. */
    List<AiAgentPrivacyWork> dueWork(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    /** Advances mutable work metadata only at the expected revision and immutable operation identity. */
    int updateWork(
            @Param("work") AiAgentPrivacyWork work,
            @Param("expectedRevision") long expectedRevision);

    /** Executes the tenant-scoped listAffectedRunIds privacy operation. */
    List<Integer> listAffectedRunIds(
            @Param("workspaceId") int workspaceId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("afterId") int afterId,
            @Param("maxId") int maxId,
            @Param("limit") int limit);

    /** Executes the tenant-scoped listAffectedSessionIds privacy operation. */
    List<Integer> listAffectedSessionIds(
            @Param("workspaceId") int workspaceId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("afterId") int afterId,
            @Param("maxId") int maxId,
            @Param("limit") int limit);

    /** Executes the tenant-scoped listAffectedTurnIds privacy operation. */
    List<Integer> listAffectedTurnIds(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("afterId") int afterId,
            @Param("maxId") int maxId,
            @Param("activeOnly") boolean activeOnly,
            @Param("limit") int limit);

    /** Executes the tenant-scoped listAffectedToolCallIds privacy operation. */
    List<Integer> listAffectedToolCallIds(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("afterId") int afterId,
            @Param("maxId") int maxId,
            @Param("limit") int limit);

    /** Executes the tenant-scoped listAffectedMessageIds privacy operation. */
    List<Integer> listAffectedMessageIds(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("afterId") int afterId,
            @Param("maxId") int maxId,
            @Param("limit") int limit);

    /** Executes the tenant-scoped deleteAffectedTools privacy operation. */
    int deleteAffectedTools(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("ids") List<Integer> ids);

    /** Deletes only enrolled messages; the session sequence authority is never changed. */
    int deleteAffectedMessages(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("ids") List<Integer> ids);

    /** Executes the tenant-scoped deleteAffectedTerminalTurns privacy operation. */
    int deleteAffectedTerminalTurns(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("ids") List<Integer> ids);

    /** Executes the tenant-scoped resetAffectedTitle privacy operation. */
    int resetAffectedTitle(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("title") String title,
            @Param("generation") long generation);

    /** Counts every affected holding without discovery watermarks or cursor exclusions. */
    long residualCount(
            @Param("workspaceId") int workspaceId,
            @Param("cutoffGeneration") long cutoffGeneration);

    /** Executes the tenant-scoped sessionResidualCount privacy operation. */
    long sessionResidualCount(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration);

    /** Executes the tenant-scoped countIncompleteWork privacy operation. */
    int countIncompleteWork(
            @Param("workspaceId") int workspaceId);

    /** Executes the tenant-scoped deleteMissionTurns privacy operation. */
    int deleteMissionTurns(
            @Param("workspaceId") int workspaceId,
            @Param("runId") int runId);

    /** Executes the tenant-scoped settleAffectedTurn privacy operation. */
    int settleAffectedTurn(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId,
            @Param("cutoffGeneration") long cutoffGeneration,
            @Param("turnId") int turnId);

    /** Executes the tenant-scoped clearSessionProjections privacy operation. */
    int clearSessionProjections(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId);
    /** Removes at most one batch of derived notification sources for an enrolled session. */
    int deleteSessionNotifications(@Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId, @Param("limit") int limit);

    /** Recomputes safe transcript preview timing without changing its sequence authority. */
    int updateSessionPreview(@Param("workspaceId") int workspaceId, @Param("sessionId") int sessionId);

    /** Locks an exact enrolled message before barrier/work acquisition. */
    Integer lockMessage(@Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId, @Param("id") int id);
    /** Discovers pre-contract active executions without copying their partial content. */
    List<ooo.klae.connex.backend.beans.AiChatTurnRef> legacyActiveTurns(
            @Param("workspaceId") int workspaceId, @Param("limit") int limit);

    /** Refuses content continuation for a pre-contract ordinary execution during startup. */
    int settleLegacyTurn(@Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId, @Param("turnId") int turnId);
}
