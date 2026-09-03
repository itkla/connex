package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Team;
import ooo.klae.connex.backend.beans.TeamMember;

/** Workspace-scoped persistence for teams and their seats. */
public interface TeamMapper {
    /** Returns teams in the workspace, optionally including archived rows. */
    List<Team> getAll(
        @Param("workspaceId") int workspaceId,
        @Param("includeArchived") boolean includeArchived);

    /** Returns one team in the workspace, including an archived row. */
    Team getById(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Locks and returns one team in the workspace, including an archived row. */
    Team getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Discovers teams that reference a user in one workspace. */
    List<Team> findReferencesForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Discovers teams that reference a user in the routed tenant catalog. */
    List<Team> findReferencesForUserAnywhere(@Param("userId") int userId);

    /** Returns every seat for a bounded team-id collection. */
    List<TeamMember> getMembersForTeams(
        @Param("workspaceId") int workspaceId,
        @Param("teamIds") List<Integer> teamIds);

    /** Returns whether the user currently holds a seat on the team. */
    boolean hasMember(
        @Param("workspaceId") int workspaceId,
        @Param("teamId") int teamId,
        @Param("userId") int userId);

    /** Locks and returns the user id when the user currently holds a seat on the team. */
    Integer lockMember(
        @Param("workspaceId") int workspaceId,
        @Param("teamId") int teamId,
        @Param("userId") int userId);

    /** Inserts a team and populates its generated id. */
    int insert(Team team);

    /** Replaces editable fields on an existing team. */
    int update(Team team);

    /** Soft-archives an active team. */
    int archive(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Creates a seat or replaces its team role. */
    int upsertMember(TeamMember member);

    /** Demotes every current manager seat on one team. */
    int demoteManagers(@Param("workspaceId") int workspaceId, @Param("teamId") int teamId);

    /** Removes one team seat. */
    int removeMember(
        @Param("workspaceId") int workspaceId,
        @Param("teamId") int teamId,
        @Param("userId") int userId);

    /** Clears a team manager reference when it names the specified user. */
    int clearManager(
        @Param("workspaceId") int workspaceId,
        @Param("teamId") int teamId,
        @Param("userId") int userId);

    /** Deletes a user's team seats during workspace membership cleanup. */
    int deleteMembershipsForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Clears a user's manager references during workspace membership cleanup. */
    int clearManagerForUser(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    /** Deletes a user's team seats across the routed tenant catalog during account erasure. */
    int deleteMembershipsAnywhere(@Param("userId") int userId);

    /** Clears a user's manager references across the routed tenant catalog during account erasure. */
    int clearManagerAnywhere(@Param("userId") int userId);
}
