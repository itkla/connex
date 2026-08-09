package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.RelationshipSignal;
import ooo.klae.connex.backend.beans.RelationshipSignalFamilyState;

/** Tenant-scoped persistence for canonical relationship signals and actor lifecycle state. */
public interface RelationshipSignalMapper {
    int upsertSignal(RelationshipSignal signal);

    int resolveMissing(
            @Param("workspaceId") int workspaceId,
            @Param("family") String family,
            @Param("generationToken") String generationToken,
            @Param("resolvedAt") LocalDateTime resolvedAt);

    int resolveByIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Long> ids,
            @Param("resolvedAt") LocalDateTime resolvedAt);

    List<RelationshipSignal> findActiveForActor(
            @Param("workspaceId") int workspaceId,
            @Param("userId") int userId);

    RelationshipSignal getActiveForActor(
            @Param("workspaceId") int workspaceId,
            @Param("signalId") long signalId,
            @Param("userId") int userId);

    RelationshipSignal getActiveForActorForUpdate(
            @Param("workspaceId") int workspaceId,
            @Param("signalId") long signalId,
            @Param("userId") int userId);

    int insertState(
            @Param("workspaceId") int workspaceId,
            @Param("signalId") long signalId,
            @Param("userId") int userId,
            @Param("disposition") String disposition,
            @Param("snoozeUntil") LocalDateTime snoozeUntil,
            @Param("dismissedSourceHash") String dismissedSourceHash);

    int updateState(
            @Param("workspaceId") int workspaceId,
            @Param("signalId") long signalId,
            @Param("userId") int userId,
            @Param("disposition") String disposition,
            @Param("snoozeUntil") LocalDateTime snoozeUntil,
            @Param("dismissedSourceHash") String dismissedSourceHash,
            @Param("expectedVersion") long expectedVersion);

    int attachTask(
            @Param("workspaceId") int workspaceId,
            @Param("signalId") long signalId,
            @Param("userId") int userId,
            @Param("taskId") int taskId,
            @Param("sourceStateHash") String sourceStateHash,
            @Param("expectedVersion") long expectedVersion);

    int upsertFamilyAvailable(
            @Param("workspaceId") int workspaceId,
            @Param("family") String family,
            @Param("attemptedAt") LocalDateTime attemptedAt,
            @Param("evidenceAsOf") LocalDateTime evidenceAsOf);

    int ensureFamilyState(
            @Param("workspaceId") int workspaceId,
            @Param("family") String family,
            @Param("attemptedAt") LocalDateTime attemptedAt);

    RelationshipSignalFamilyState lockFamilyState(
            @Param("workspaceId") int workspaceId,
            @Param("family") String family);

    int upsertFamilyUnavailable(
            @Param("workspaceId") int workspaceId,
            @Param("family") String family,
            @Param("attemptedAt") LocalDateTime attemptedAt,
            @Param("errorCode") String errorCode);

    List<RelationshipSignalFamilyState> findFamilyStates(@Param("workspaceId") int workspaceId);

    int deleteActorState(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    int deleteActorStateAnywhere(@Param("userId") int userId);
}
