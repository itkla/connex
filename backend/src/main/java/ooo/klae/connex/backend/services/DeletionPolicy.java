package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.WorkspaceService.Role;

/** Enforces creator-or-admin deletion for workspace-owned content. */
@Component
@RequiredArgsConstructor
public class DeletionPolicy {
    private final WorkspaceService workspaceService;

    /**
     * Allows the creator or a current workspace admin or owner to delete the content. A missing
     * creator fails closed to the admin-or-owner requirement.
     *
     * @param creatorUserId creator user id, or {@code null} when creator identity is unavailable
     */
    public void requireDeletable(Integer creatorUserId) {
        if (creatorUserId != null && creatorUserId == workspaceService.getCurrentUserId()) {
            return;
        }
        workspaceService.requireRole(Role.ADMIN);
    }
}
