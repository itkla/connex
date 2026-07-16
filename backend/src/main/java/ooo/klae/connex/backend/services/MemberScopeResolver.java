package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Resolves canonical member scopes and validates selected ids against active workspace membership. */
@Service
@RequiredArgsConstructor
public class MemberScopeResolver {
    private final WorkspaceService workspaceService;

    /**
     * Resolves request parameters into a workspace-validated member scope.
     *
     * @param scope raw request scope
     * @param memberIds raw selected member ids
     * @param currentUserId authenticated current user id
     * @return canonical member scope
     */
    public MemberScope resolve(String scope, List<Integer> memberIds, int currentUserId) {
        MemberScope resolved = MemberScope.fromRequest(scope, memberIds, currentUserId);
        if (resolved.mode() != MemberScope.Mode.MEMBERS) {
            return resolved;
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Set<Integer> activeMemberIds = workspaceService.getMembers(workspaceId).stream()
            .map(User::getId)
            .collect(Collectors.toUnmodifiableSet());
        if (!activeMemberIds.containsAll(resolved.memberIds())) {
            throw new BadRequestException(
                "memberIds must contain only active members of the current workspace");
        }
        return resolved;
    }
}
