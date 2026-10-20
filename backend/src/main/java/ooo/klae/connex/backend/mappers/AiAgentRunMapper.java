package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiAgentSegment;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatToolCall;

/** Tenant-scoped mission persistence; services own root locking and final lease operations. */
@Mapper
public interface AiAgentRunMapper {

    /** Executes the tenant-scoped insertRun persistence operation. */
    int insertRun(
            @Param("run") AiAgentRun run);

    /** Executes the tenant-scoped getRun persistence operation. */
    AiAgentRun getRun(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    /** Locks an exact run and flushes stale transaction-local observations. */
    AiAgentRun lockRun(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    /** Executes the tenant-scoped findByRequestKey persistence operation. */
    AiAgentRun findByRequestKey(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("clientRequestKey") String clientRequestKey);

    /** Executes the tenant-scoped listRuns persistence operation. */
    List<AiAgentRun> listRuns(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("sessionId") Integer sessionId,
            @Param("state") String state,
            @Param("beforeId") Integer beforeId,
            @Param("limit") int limit,
            @Param("organizationPrivacyGeneration") long organizationPrivacyGeneration);

    /** Executes the tenant-scoped dueRuns persistence operation. */
    List<AiAgentRun> dueRuns(
            @Param("workspaceId") int workspaceId,
            @Param("limit") int limit);

    /** Discovers active attempts without a live lease after migration or restart. */
    List<AiAgentRun> orphanedRuns(@Param("workspaceId") int workspaceId, @Param("limit") int limit);

    /** Verifies the required running run, segment and turn identity without taking lease locks. */
    int countInconsistentActiveRuns(@Param("workspaceId") int workspaceId);

    /** Discovers expired nonterminal capabilities independently of provider readiness. */
    List<AiAgentRun> expiredRuns(@Param("workspaceId") int workspaceId, @Param("limit") int limit);

    /** Executes the tenant-scoped countNonterminalBySession persistence operation. */
    int countNonterminalBySession(
            @Param("workspaceId") int workspaceId,
            @Param("sessionId") int sessionId);

    /** Updates mutable run state only for the expected version and execution generation. */
    int updateRun(
            @Param("run") AiAgentRun run,
            @Param("expectedStateVersion") long expectedStateVersion,
            @Param("expectedExecutionGeneration") long expectedExecutionGeneration);

    /** Executes the tenant-scoped insertSegment persistence operation. */
    int insertSegment(
            @Param("segment") AiAgentSegment segment);

    /** Executes the tenant-scoped getSegment persistence operation. */
    AiAgentSegment getSegment(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    /** Locks an exact attempt after its run and turn roots. */
    AiAgentSegment lockSegment(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    /** Executes the tenant-scoped listSegments persistence operation. */
    List<AiAgentSegment> listSegments(
            @Param("workspaceId") int workspaceId,
            @Param("runId") int runId);

    /** Updates attempt state without changing its immutable execution identity. */
    int updateSegment(
            @Param("segment") AiAgentSegment segment,
            @Param("expectedStatus") String expectedStatus);

    /** Severs only terminal attempt links before deleting their turns. */
    int detachSegmentTurns(
            @Param("workspaceId") int workspaceId,
            @Param("runId") int runId);

    /** Executes the tenant-scoped deleteSegments persistence operation. */
    int deleteSegments(
            @Param("workspaceId") int workspaceId,
            @Param("runId") int runId);

    /** Clears erased-account ownership only after content has become a tombstone. */
    int clearErasedOwner(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId);

    /** Executes the tenant-scoped ownerRunIds persistence operation. */
    List<Integer> ownerRunIds(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("afterId") int afterId,
            @Param("limit") int limit);
    /** Discovers only numeric workspace keys for erased-owner maintenance in the installed catalog. */
    List<Integer> ownerWorkspaceIdsAnywhere(@Param("ownerUserId") int ownerUserId);

    /** Discovers a bounded set of purged owner references for post-delete reconciliation. */
    List<Integer> purgedOwnerIds(@Param("workspaceId") int workspaceId,
            @Param("afterOwnerId") int afterOwnerId, @Param("limit") int limit);
    /** Counts pre-departure capability under a fresh metadata snapshot before a new membership. */
    int countNonterminalForOwner(@Param("workspaceId") int workspaceId, @Param("ownerUserId") int ownerUserId);

    /** Discovers a bounded keyset of nonterminal mission roots for permission-independent maintenance. */
    List<AiAgentRun> nonterminalRunsAfter(@Param("workspaceId") int workspaceId,
            @Param("afterId") int afterId, @Param("limit") int limit);

    /** Writes canonical continuation under the exact active generation and both revisions. */
    int updateContinuation(@Param("run") AiAgentRun run,
            @Param("expectedStateVersion") long expectedStateVersion,
            @Param("expectedExecutionGeneration") long expectedExecutionGeneration,
            @Param("expectedActiveSegmentId") Integer expectedActiveSegmentId,
            @Param("expectedPlanRevision") long expectedPlanRevision,
            @Param("expectedCheckpointRevision") long expectedCheckpointRevision);

    /** Loads goal ancestry without its prose. */
    AiChatMessage goalMessageIdentity(@Param("workspaceId") int workspaceId, @Param("runId") int runId);

    /** Discovers stopped READ attempts without obtaining execution or lease authority. */
    List<AiAgentRun> interruptedReadRuns(@Param("workspaceId") int workspaceId, @Param("limit") int limit);

    /** Atomically reserves one exact dispatch ordinal before egress. */
    int reserveSegmentDispatch(@Param("workspaceId") int workspaceId, @Param("runId") int runId,
            @Param("segmentId") int segmentId, @Param("generation") long generation,
            @Param("expectedOrdinal") int expectedOrdinal, @Param("tokenCeiling") long tokenCeiling);

    /** Charges one outstanding dispatch, refusing duplicates and successor callbacks. */
    int recordDispatchUsage(@Param("workspaceId") int workspaceId, @Param("runId") int runId,
            @Param("segmentId") int segmentId, @Param("generation") long generation,
            @Param("ordinal") int ordinal, @Param("input") int input, @Param("output") int output);

    /** Persists screened mission replay payloads using lossless UTF-8 transport. */
    int insertMissionBookkeepingCall(@Param("call") AiChatToolCall call);

    /** Locks only an exact attempt-local bookkeeping identity before turn and segment roots. */
    AiChatToolCall findMissionBookkeepingCall(@Param("workspaceId") int workspaceId, @Param("runId") int runId,
            @Param("idempotencyKey") String idempotencyKey);

}
