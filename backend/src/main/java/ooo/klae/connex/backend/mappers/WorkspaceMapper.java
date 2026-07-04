package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;

/**
 * Mapper for workspace and membership persistence.
 */
public interface WorkspaceMapper {
    List<Workspace> getWorkspacesForUser(int userId);
    List<WorkspaceMembershipDto> getMembershipsForUser(int userId);
    Workspace getDefaultWorkspace();
    boolean isMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    boolean isMemberIncludingPending(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    String getRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<User> getMembers(int workspaceId);
    List<MemberDto> getMembersWithRoles(int workspaceId);
    MemberDto getMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    Integer getMemberRoleId(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int setMemberCustomRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId, @Param("roleId") int roleId);
    int countOwners(int workspaceId);
    java.util.List<Integer> workspaceIdsOwnedBy(@Param("userId") int userId);
    java.util.List<Integer> lockOwnerIds(@Param("workspaceId") int workspaceId);
    int removeMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int unassignMemberTasks(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int clearMemberDealOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    Integer getLastActiveWorkspaceId(int userId);
    int setLastActiveWorkspaceId(@Param("userId") int userId, @Param("workspaceId") int workspaceId);
    int insert(Workspace workspace);
    Integer getOrgId(int workspaceId);
    int countEnforcingSsoMemberships(int userId);
    int addMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    int addPendingMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    int activateMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<WorkspaceMembershipDto> getPendingMemberships(int userId);
    int updateMemberRole(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
}