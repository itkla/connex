package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/** Tenant-scoped persistence for expiring one-use deal duplicate review proofs. */
public interface DealDuplicateReviewProofMapper {

    int deleteExpired(
        @Param("workspaceId") int workspaceId,
        @Param("limit") int limit);

    List<Integer> workspaceIdsWithExpired(@Param("limit") int limit);

    int insert(
        @Param("tokenHash") byte[] tokenHash,
        @Param("workspaceId") int workspaceId,
        @Param("actorId") int actorId,
        @Param("workflowHash") byte[] workflowHash,
        @Param("resultHash") byte[] resultHash,
        @Param("ttlSeconds") long ttlSeconds);

    Integer lockConsumable(
        @Param("tokenHash") byte[] tokenHash,
        @Param("workspaceId") int workspaceId,
        @Param("actorId") int actorId,
        @Param("workflowHash") byte[] workflowHash,
        @Param("resultHash") byte[] resultHash);

    int deleteClaimed(
        @Param("tokenHash") byte[] tokenHash,
        @Param("workspaceId") int workspaceId);

    int deleteForActor(
        @Param("workspaceId") int workspaceId,
        @Param("actorId") int actorId);

    int deleteForActorAnywhere(@Param("actorId") int actorId);
}
