package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;

/**
 * Mapper for workspace and membership persistence.
 */
public interface WorkspaceMapper {
    List<Workspace> getWorkspacesForUser(int userId);
    List<WorkspaceMembershipDto> getMembershipsForUser(int userId);
    Workspace getDefaultWorkspace();
    boolean isMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    String getRole(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<User> getMembers(int workspaceId);
    Integer getLastActiveWorkspaceId(int userId);
    int setLastActiveWorkspaceId(@Param("userId") int userId, @Param("workspaceId") int workspaceId);
    int insert(Workspace workspace);
    int addMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
    int updateMemberRole(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
}