package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderCaptureUserPolicy;
import ooo.klae.connex.backend.beans.ProviderCaptureWorkspacePolicy;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.dto.ProviderCaptureSyncRef;
import ooo.klae.connex.backend.dto.ProviderCaptureDiagnosticsRow;

/**
 * Tenant-scoped persistence for connected capture policy, source, review, and sync state.
 */
public interface ProviderCaptureMapper {
    List<ProviderCaptureDiagnosticsRow> findDiagnosticsAggregates(
        @Param("workspaceId") int workspaceId,
        @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);
    ProviderCaptureWorkspacePolicy getWorkspacePolicy(
        @Param("workspaceId") int workspaceId, @Param("provider") String provider);
    int insertWorkspacePolicy(ProviderCaptureWorkspacePolicy policy);
    int updateWorkspacePolicy(
        @Param("policy") ProviderCaptureWorkspacePolicy policy,
        @Param("expectedVersion") long expectedVersion);
    ProviderCaptureUserPolicy getUserPolicy(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    List<Integer> getEnabledUserPolicyUserIds(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int insertUserPolicy(ProviderCaptureUserPolicy policy);
    int updateUserPolicy(
        @Param("policy") ProviderCaptureUserPolicy policy,
        @Param("expectedVersion") long expectedVersion);
    int ensureSyncState(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("credentialGeneration") long credentialGeneration);
    List<ProviderCaptureSyncState> getSyncStates(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    List<ProviderCaptureSyncRef> findDueSyncRefs(
        @Param("workspaceId") int workspaceId,
        @Param("now") String now,
        @Param("limit") int limit);
    int queueSync(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int pauseUserSync(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int waitManualSync(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int pauseWorkspaceSync(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int resumeWorkspaceSync(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int resetSyncGeneration(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("credentialGeneration") long credentialGeneration);
    int claimSync(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("now") String now,
        @Param("until") String until);
    int renewSyncLease(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("now") String now,
        @Param("until") String until);
    ProviderCaptureSyncState getSyncStateForUpdate(
        @Param("workspaceId") int workspaceId, @Param("id") long id);
    ProviderCaptureSyncState getSyncState(
        @Param("workspaceId") int workspaceId, @Param("id") long id);
    int saveSyncSuccess(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("stableCursor") String stableCursor,
        @Param("pageCursor") String pageCursor,
        @Param("status") String status,
        @Param("processedItems") long processedItems,
        @Param("estimatedItems") Long estimatedItems,
        @Param("lastSuccessAt") String lastSuccessAt,
        @Param("nextAttemptAt") String nextAttemptAt);
    int saveSyncFailure(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("status") String status,
        @Param("errorCode") String errorCode,
        @Param("nextAttemptAt") String nextAttemptAt);
    int resetSyncCursorFailure(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("errorCode") String errorCode,
        @Param("nextAttemptAt") String nextAttemptAt);
    int pauseClaimedSync(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("owner") String owner,
        @Param("errorCode") String errorCode);
    ProviderCapturedInteraction getInteractionBySourceHash(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("sourceKeyHash") byte[] sourceKeyHash);
    ProviderCapturedInteraction getInteractionForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("id") long id);
    int insertInteraction(ProviderCapturedInteraction interaction);
    int updateInteraction(ProviderCapturedInteraction interaction);
    int touchInteractionReconciliationMarker(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("marker") String marker,
        @Param("sourceVersion") String sourceVersion);
    int touchInteractionActivitiesCapturedAt(
        @Param("workspaceId") int workspaceId,
        @Param("interactionId") long interactionId);
    int deleteMissingReconciliationActivities(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    int deleteMissingReconciliationParticipants(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    int withdrawMissingReconciliationItems(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    int deleteParticipants(
        @Param("workspaceId") int workspaceId, @Param("interactionId") long interactionId);
    int insertParticipant(ProviderCapturedParticipant participant);
    List<ProviderCapturedParticipant> getParticipants(
        @Param("workspaceId") int workspaceId, @Param("interactionId") long interactionId);
    List<Integer> getReconciledPersonIds(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    List<Integer> getReconciledActivityIds(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    List<Integer> getPersonIdsForInteractions(
        @Param("workspaceId") int workspaceId,
        @Param("interactionIds") List<Long> interactionIds);
    List<Integer> getActivityIdsForInteractions(
        @Param("workspaceId") int workspaceId,
        @Param("interactionIds") List<Long> interactionIds);
    List<Long> getMissingReconciliationInteractionIds(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("marker") String marker);
    List<ProviderCapturedParticipant> getReviewPage(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("limit") int limit,
        @Param("offset") int offset);
    long countReviews(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    long countPendingApprovals(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    ProviderCapturedParticipant getParticipantForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("id") long id);
    ProviderCapturedParticipant getParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("id") long id);
    int resolveParticipant(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedVersion") long expectedVersion,
        @Param("matchState") String matchState,
        @Param("personId") Integer personId);
    int upsertDecision(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("normalizedEmail") String normalizedEmail,
        @Param("decision") String decision,
        @Param("personId") Integer personId);
    Integer getRememberedPersonId(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("normalizedEmail") String normalizedEmail);
    boolean isRememberedIgnore(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("normalizedEmail") String normalizedEmail);
    boolean isPersonProcessingRestricted(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId);
    int insertProviderActivity(
        @Param("workspaceId") int workspaceId,
        @Param("interaction") ProviderCapturedInteraction interaction,
        @Param("participant") ProviderCapturedParticipant participant,
        @Param("actorId") int actorId,
        @Param("projectionKey") String projectionKey);
    int updateProviderActivity(
        @Param("workspaceId") int workspaceId,
        @Param("interaction") ProviderCapturedInteraction interaction,
        @Param("participant") ProviderCapturedParticipant participant,
        @Param("projectionKey") String projectionKey);
    int deleteInteractionActivities(
        @Param("workspaceId") int workspaceId,
        @Param("interactionId") long interactionId);
    int deleteInteractionActivitiesExceptPeople(
        @Param("workspaceId") int workspaceId,
        @Param("interactionId") long interactionId,
        @Param("personIds") List<Integer> personIds);
    int deleteExpiredProviderActivities(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("before") String before);
    int deleteExpiredInteractions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("stream") String stream,
        @Param("before") String before);
    Integer getActivityIdByProjectionKey(
        @Param("workspaceId") int workspaceId, @Param("projectionKey") String projectionKey);
    int insertProjection(
        @Param("workspaceId") int workspaceId,
        @Param("interactionId") long interactionId,
        @Param("participantId") long participantId,
        @Param("activityId") int activityId);
    int markInteractionAdmitted(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedVersion") long expectedVersion);
    int markInteractionIgnored(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedVersion") long expectedVersion);
    int withdrawRestrictedProjections(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int holdRestrictedInteractions(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int unmatchRestrictedParticipants(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int releaseRestoredParticipantReviews(
        @Param("workspaceId") int workspaceId, @Param("personId") int personId);
    int clearWorkspacePolicyUpdater(
        @Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int clearWorkspacePolicyUpdaterAnywhere(@Param("userId") int userId);
    int deleteProviderActivities(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteProviderActivitiesAnywhere(
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteWorkspaceProviderActivities(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int deleteInteractions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteInteractionsAnywhere(
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteWorkspaceProviderInteractions(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int deleteSyncStates(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteSyncStatesAnywhere(
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteWorkspaceProviderSyncStates(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int resetWorkspaceProviderSyncStates(
        @Param("workspaceId") int workspaceId,
        @Param("provider") String provider);
    int deleteUserPolicy(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteUserPolicyAnywhere(
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteDecisions(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
    int deleteDecisionsAnywhere(
        @Param("userId") int userId,
        @Param("provider") String provider);
    long countUserProviderResiduals(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("provider") String provider);
}
