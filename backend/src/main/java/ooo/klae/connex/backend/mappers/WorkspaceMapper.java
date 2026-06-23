package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

/**
 * Mapper for workspace and membership persistence.
 */
public interface WorkspaceMapper {
    List<Workspace> getWorkspacesForUser(int userId);
    Workspace getDefaultWorkspace();
    boolean isMember(@Param("workspaceId") int workspaceId, @Param("userId") int userId);
    List<User> getMembers(int workspaceId);
    int insert(Workspace workspace);
    int addMember(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("role") String role
    );
}