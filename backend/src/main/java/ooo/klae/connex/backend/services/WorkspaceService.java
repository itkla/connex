package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Resolves the single active workspace while workspace switching is disabled.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final WorkspaceMapper workspaceMapper;

    public Workspace getCurrentWorkspace() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)) {
            throw new ForbiddenException("Authentication is required to resolve a workspace");
        }
        List<Workspace> workspaces = workspaceMapper.getWorkspacesForUser(user.getId());
        if (workspaces.isEmpty()) {
            throw new ForbiddenException("The authenticated user does not belong to a workspace");
        }
        if (workspaces.size() > 1) {
            throw new IllegalStateException("Workspace switching is unavailable for users with multiple memberships");
        }
        return workspaces.getFirst();
    }

    public int getCurrentWorkspaceId() {
        return getCurrentWorkspace().getId();
    }

    public void requireMember(int workspaceId, int userId) {
        if (!isMember(workspaceId, userId)) {
            throw new BadRequestException("User " + userId + " is not a member of this workspace");
        }
    }

    public boolean isMember(int workspaceId, int userId) {
        return workspaceMapper.isMember(workspaceId, userId);
    }

    public List<User> getMembers(int workspaceId) {
        return workspaceMapper.getMembers(workspaceId);
    }
}