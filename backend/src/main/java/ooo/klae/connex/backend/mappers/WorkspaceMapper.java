package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;

/**
 * Mapper for workspace and membership persistence.
 */
public interface WorkspaceMapper {
    List<Workspace> getWorkspacesForUser(int userId);
    List<WorkspaceMembershipDto> getMembershipsForUser(int userId);
    /** Returns the exact active membership through a current shared-locking read. */
    WorkspaceMembershipDto getMembershipForUserForShare(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    Workspace getDefaultWorkspace();
    boolean isMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    /**
     * Locks the caller's active membership row for the given user ({@code SELECT ... FOR UPDATE}) and
     * returns the user id, or {@code null} when there is no active membership. Must be called inside a
     * transaction so the row lock is held; it serializes member-backed assignments against the offboarding
     * membership lock ({@code NotificationMapper.lockRecipientMemberships}) so a departing member cannot
     * receive a member-backed assignment concurrently with their removal.
     */
    Integer lockActiveMembership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    WorkspaceMember lockAuthorizationMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);
    boolean isMemberIncludingPending(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int countActiveMembers(
        @Param("workspaceId") int workspaceId,
        @Param("memberIds") List<Integer> memberIds
    );
    String getRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<User> getMembers(int workspaceId);
    List<MemberDto> getMembersWithRoles(int workspaceId);
    MemberDto getMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    Integer getMemberRoleId(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    int setMemberCustomRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId, @Param("roleId") int roleId);
    int countOwners(int workspaceId);
    java.util.List<Integer> workspaceIdsOwnedBy(@Param("userId") int userId);
    Integer lockWorkspace(@Param("workspaceId") int workspaceId);
    Integer lockWorkspaceForShare(@Param("workspaceId") int workspaceId);
    java.util.List<Integer> lockOwnerIds(@Param("workspaceId") int workspaceId);
    int removeMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
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
