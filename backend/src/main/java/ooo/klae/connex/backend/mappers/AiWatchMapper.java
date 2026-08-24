package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiWatch;

/** Tenant-local persistence for typed Ask Connex watches. */
@Mapper
public interface AiWatchMapper {

    List<AiWatch> listForOwner(
            @Param("workspaceId") int workspaceId, @Param("ownerUserId") int ownerUserId);

    AiWatch findForOwner(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("id") int id);

    int countForOwner(
            @Param("workspaceId") int workspaceId, @Param("ownerUserId") int ownerUserId);

    int insert(@Param("watch") AiWatch watch);

    int updateStatus(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("id") int id,
            @Param("status") String status);

    int delete(
            @Param("workspaceId") int workspaceId,
            @Param("ownerUserId") int ownerUserId,
            @Param("id") int id);

    /** Lists active, unexpired watches for one workspace in deterministic evaluation order. */
    List<AiWatch> findEvaluable(
            @Param("workspaceId") int workspaceId, @Param("today") String today);

    int recordEvaluated(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("at") String at);

    /**
     * Claims one firing, at most once. A repeat evaluation producing the same deterministic state
     * token inside the declared cooldown affects no rows, which is what makes replay, backfill, and
     * every-sweep re-evaluation incapable of flooding a member.
     */
    int claimFiring(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("state") String state,
            @Param("cooldownCutoff") String cooldownCutoff,
            @Param("at") String at);

    int deleteForUser(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    int deleteForUserAnywhere(@Param("userId") int userId);
}
